"""Exercise actual migration SQL and generated Room schema against a populated v2 database."""
from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/com/dcimgallery/raw/data/db/AppDatabase.kt').read_text(encoding='utf-8')
generated = (root / 'app/build/generated/ksp/release/java/com/dcimgallery/raw/data/db/AppDatabase_Impl.java').read_text(encoding='utf-8')
sql = re.findall(r'\.execSQL\("([^"\n]+)"\)', generated)
tables = [s for s in sql if s.startswith('CREATE TABLE') and 'room_master_table' not in s]
indices = [s for s in sql if s.startswith('CREATE INDEX')]
assert len(tables) == 2 and len(indices) == 1
old_schema = next(s for s in tables if '`photos`' in s)
db = sqlite3.connect(':memory:')
db.execute(old_schema)
rows = [(f'photo-{i}', f'IMG_{i}.CR3', 40000000, i * 1000, i * 2000, 'DCIM', 'external') for i in range(50000)]
db.executemany('INSERT INTO photos (uri,displayName,sizeBytes,lastModified,takenAt,folder,source) VALUES (?,?,?,?,?,?,?)', rows)
before = db.execute('SELECT * FROM photos ORDER BY uri').fetchall()
for statement in re.findall(r'db\.execSQL\("([^"\n]+)"\)', source):
    db.execute(statement)
assert db.execute('SELECT * FROM photos ORDER BY uri').fetchall() == before
expected = sqlite3.connect(':memory:')
for statement in tables + indices:
    expected.execute(statement)
for name in ('photos', 'photo_formats'):
    assert db.execute(f'PRAGMA table_info({name})').fetchall() == expected.execute(f'PRAGMA table_info({name})').fetchall()
    assert db.execute(f'PRAGMA index_list({name})').fetchall() == expected.execute(f'PRAGMA index_list({name})').fetchall()
query = 'SELECT * FROM photos ORDER BY takenAt DESC, displayName DESC, uri DESC'
plan = ' '.join(str(r) for r in db.execute('EXPLAIN QUERY PLAN ' + query))
assert 'index_photos_takenAt_displayName_uri' in plan and 'TEMP B-TREE' not in plan, plan
db.executemany('INSERT INTO photo_formats VALUES (?,?,?,?)', [('photo-1', 40000000, 1000, 'CR3'), ('missing', 1, 1, 'JPEG')])
dao = (root / 'app/src/main/java/com/dcimgallery/raw/data/db/PhotoFormatDao.kt').read_text(encoding='utf-8')
cleanup = re.search(r'@Query\("(DELETE FROM photo_formats[^"\n]+)"\)', dao).group(1)
db.execute(cleanup)
assert db.execute('SELECT uri FROM photo_formats').fetchall() == [('photo-1',)]
assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
print('PASS: v2->v3 preserves 50,000 rows; migration matches generated Room schema; ordered query uses index; format cache orphan cleanup verified')
