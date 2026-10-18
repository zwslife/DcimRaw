#pragma once
// Portable rendering core shared by JNI preview, full-size output and masks.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>
#include <deque>
#include <thread>
#include <mutex>
#include <exception>

namespace dcim {
struct RenderData {
    float m[20]{};
    float lut[768]{}; // Composed red, green, blue tables, 256 entries each.
    float hsl[24]{};  // 8 区 × [色相°, 饱和度Δ, 亮度Δ]
    bool hasHsl = false;
    float effects[39]{};
    float grade[4][3]{};
    bool hasEffects = false;
    bool hasGrade = false;
    const float* sharpMap = nullptr;
};

const float HSL_CENTERS[8] = {0.f, 30.f, 60.f, 120.f, 180.f, 240.f, 270.f, 315.f};

inline int clamp255(float v) {
    return v < 0.f ? 0 : (v > 255.f ? 255 : static_cast<int>(v + 0.5f));
}

inline float clamp01(float v) {
    return v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
}

inline float circDist(float a, float b) {
    float d = std::fabs(a - b);
    return d > 180.f ? 360.f - d : d;
}

void rgbToHsl(float r, float g, float b, float& h, float& s, float& l) {
    float mx = std::max(r, std::max(g, b));
    float mn = std::min(r, std::min(g, b));
    l = (mx + mn) * 0.5f;
    if (mx == mn) {
        h = 0.f;
        s = 0.f;
        return;
    }
    float d = mx - mn;
    s = l > 0.5f ? d / (2.f - mx - mn) : d / (mx + mn);
    if (mx == r) {
        h = 60.f * std::fmod((g - b) / d + 6.f, 6.f);
    } else if (mx == g) {
        h = 60.f * ((b - r) / d + 2.f);
    } else {
        h = 60.f * ((r - g) / d + 4.f);
    }
}

inline float hueToRgb(float p, float q, float t) {
    if (t < 0.f) t += 1.f;
    if (t > 1.f) t -= 1.f;
    if (t < 1.f / 6.f) return p + (q - p) * 6.f * t;
    if (t < 1.f / 2.f) return q;
    if (t < 2.f / 3.f) return p + (q - p) * (2.f / 3.f - t) * 6.f;
    return p;
}

void hslToRgb(float h, float s, float l, float& r, float& g, float& b) {
    if (s <= 1e-6f) {
        r = g = b = l;
        return;
    }
    float q = l < 0.5f ? l * (1.f + s) : l + s - l * s;
    float p = 2.f * l - q;
    float hn = std::fmod(h, 360.f) / 360.f;
    if (hn < 0.f) hn += 1.f;
    r = hueToRgb(p, q, hn + 1.f / 3.f);
    g = hueToRgb(p, q, hn);
    b = hueToRgb(p, q, hn - 1.f / 3.f);
}

inline bool isNeutralData(const RenderData& d) {
    if (d.hasEffects) return false;
    for (float v : d.hsl) {
        if (v != 0.f) return false;
    }
    for (int i = 0; i < 768; i++) {
        if (std::fabs(d.lut[i] - (i % 256)) > 0.5f) return false;
    }
    // 矩阵是否单位阵（仅检查非对角偏移与增益）
    static const float ID[20] = {1,0,0,0,0, 0,1,0,0,0, 0,0,1,0,0, 0,0,0,1,0};
    for (int i = 0; i < 20; i++) {
        if (std::fabs(d.m[i] - ID[i]) > 1e-5f) return false;
    }
    return true;
}

inline void processPixel(const RenderData& d, uint8_t& R, uint8_t& G, uint8_t& B) {
    float r = R, g = G, b = B;
    float nr = d.m[0] * r + d.m[1] * g + d.m[2] * b + d.m[4];
    float ng = d.m[5] * r + d.m[6] * g + d.m[7] * b + d.m[9];
    float nb = d.m[10] * r + d.m[11] * g + d.m[12] * b + d.m[14];
    nr = d.lut[clamp255(nr)];
    ng = d.lut[256 + clamp255(ng)];
    nb = d.lut[512 + clamp255(nb)];

    // 基础/色调调整无需经过 RGB↔HSL，尤其避免每帧数百万次取模与除法。
    if (!d.hasHsl) {
        R = static_cast<uint8_t>(clamp255(nr));
        G = static_cast<uint8_t>(clamp255(ng));
        B = static_cast<uint8_t>(clamp255(nb));
        return;
    }

    float fr = nr / 255.f, fg = ng / 255.f, fb = nb / 255.f;
    float h, s, l;
    rgbToHsl(fr, fg, fb, h, s, l);
    if (s > 1e-4f) {
        float hueShift = 0.f, satD = 0.f, lumD = 0.f;
        for (int i = 0; i < 8; i++) {
            float w = 1.f - circDist(h, HSL_CENTERS[i]) / 45.f;
            if (w > 0.f) {
                hueShift += w * d.hsl[i * 3];
                satD += w * d.hsl[i * 3 + 1];
                lumD += w * d.hsl[i * 3 + 2];
            }
        }
        h += hueShift;
        s = clamp01(s * (1.f + satD));
        l = clamp01(l + lumD);
        hslToRgb(h, s, l, fr, fg, fb);
    }
    R = static_cast<uint8_t>(clamp255(fr * 255.f));
    G = static_cast<uint8_t>(clamp255(fg * 255.f));
    B = static_cast<uint8_t>(clamp255(fb * 255.f));
}

// Keep indices stable: PhotoEditStore v2 persists this same layout.
enum EffectIndex { Vibrance, Texture, Clarity, Dehaze, Vignette, VignetteMidpoint,
    VignetteFeather, VignetteRoundness, VignetteHighlights, Grain, GrainSize,
    GrainRoughness, BlackWhite, Sharpening, SharpenRadius, SharpenDetail,
    SharpenMasking, GradeBalance, GradeBlending, Grades = 19, BwMix = 31, EffectCount = 39 };

inline float luminance(float r, float g, float b) { return .299f*r + .587f*g + .114f*b; }
inline float smooth(float a, float b, float v) {
    const float t = clamp01((v-a) / std::max(.0001f, b-a));
    return t*t*(3.f-2.f*t);
}

inline void prepare(RenderData& d) {
    d.sharpMap = nullptr;
    d.hasHsl = false;
    for (float v : d.hsl) if (v != 0.f) d.hasHsl = true;
    d.hasEffects = false;
    for (int i : {Vibrance, Texture, Clarity, Dehaze, Vignette, Grain, BlackWhite, Sharpening})
        if (d.effects[i] != 0.f) d.hasEffects = true;
    d.hasGrade = false;
    for (int i=0; i<4; ++i) {
        const float* g = d.effects + Grades + i*3;
        if (g[1] != 0.f || g[2] != 0.f) d.hasGrade = d.hasEffects = true;
        float r, green, b;
        hslToRgb(g[0]*360.f, 1.f, .5f, r, green, b);
        const float l = luminance(r, green, b);
        d.grade[i][0] = (r-l)*g[1]*.35f;
        d.grade[i][1] = (green-l)*g[1]*.35f;
        d.grade[i][2] = (b-l)*g[1]*.35f;
    }
}

struct ImageView {
    uint8_t* data;
    int width, height, stride, channels;
    const uint8_t* pixel(int x, int y) const {
        x = std::clamp(x, 0, width-1); y = std::clamp(y, 0, height-1);
        return data + static_cast<size_t>(y)*stride + x*channels;
    }
};

// Separable sliding windows: cost is independent of radius, with replicated edges.
inline std::vector<float> boxBlur(const std::vector<float>& in, int w, int h, int radius) {
    if (radius <= 0) return in;
    std::vector<float> tmp(in.size()), out(in.size());
    const float inv = 1.f/(radius*2+1);
    for (int y=0; y<h; ++y) {
        const float* row = in.data()+static_cast<size_t>(y)*w;
        double sum=0;
        for (int dx=-radius; dx<=radius; ++dx) sum += row[std::clamp(dx,0,w-1)];
        for (int x=0; x<w; ++x) {
            tmp[static_cast<size_t>(y)*w+x] = static_cast<float>(sum)*inv;
            sum += row[std::min(w-1,x+radius+1)]-row[std::max(0,x-radius)];
        }
    }
    for (int x=0; x<w; ++x) {
        double sum=0;
        for (int dy=-radius; dy<=radius; ++dy) sum += tmp[static_cast<size_t>(std::clamp(dy,0,h-1))*w+x];
        for (int y=0; y<h; ++y) {
            out[static_cast<size_t>(y)*w+x] = static_cast<float>(sum)*inv;
            sum += tmp[static_cast<size_t>(std::min(h-1,y+radius+1))*w+x]-
                tmp[static_cast<size_t>(std::max(0,y-radius))*w+x];
        }
    }
    return out;
}

inline std::vector<float> minimumFilter(const std::vector<float>& in, int w, int h, int radius) {
    std::vector<float> tmp(in.size()), out(in.size());
    auto line = [radius](int n, auto get, auto set) {
        std::deque<int> q;
        int next=0;
        for (int i=0; i<n; ++i) {
            const int end=std::min(n-1,i+radius);
            while (next<=end) {
                while (!q.empty() && get(q.back())>=get(next)) q.pop_back();
                q.push_back(next++);
            }
            while (!q.empty() && q.front()<i-radius) q.pop_front();
            set(i,get(q.front()));
        }
    };
    for (int y=0; y<h; ++y) line(w, [&](int x){return in[static_cast<size_t>(y)*w+x];},
        [&](int x,float v){tmp[static_cast<size_t>(y)*w+x]=v;});
    for (int x=0; x<w; ++x) line(h, [&](int y){return tmp[static_cast<size_t>(y)*w+x];},
        [&](int y,float v){out[static_cast<size_t>(y)*w+x]=v;});
    return out;
}

/** Spatial analysis is capped at 1024 px and cached for the immutable preview source.
 * Full output uses exactly the same normalized analysis size, avoiding radius changes
 * between preview and export. Sharpening separately samples original-resolution pixels.
 */
struct Analysis {
    int w=0, h=0;
    std::vector<float> fine, medium, coarse, dark;
    float air[3]{.9f,.9f,.9f};
    struct SharpMap { float radius; std::vector<float> values; };
    std::deque<SharpMap> sharpMaps;
    void clear() { w=h=0; fine.clear(); medium.clear(); coarse.clear(); dark.clear(); sharpMaps.clear(); }
    void retainSharpening(const std::vector<float>& radii) {
        for(auto it=sharpMaps.begin();it!=sharpMaps.end();) {
            if(std::find(radii.begin(),radii.end(),it->radius)==radii.end())it=sharpMaps.erase(it);
            else ++it;
        }
    }
    const float* sharpening(const ImageView& src,float radius) {
        // Cache interactive frames only; full-size output keeps its bounded row buffer.
        if(static_cast<int64_t>(src.width)*src.height>1024*1024)return nullptr;
        for(const auto& map:sharpMaps)if(map.radius==radius)return map.values.data();
        if(sharpMaps.size()>=4)return nullptr;
        const int sw=src.width,sh=src.height;const size_t n=static_cast<size_t>(sw)*sh;
        std::vector<float> lum(n),horizontal(n),highpass(n);
        for(int y=0;y<sh;++y)for(int x=0;x<sw;++x) {
            const auto* p=src.pixel(x,y);lum[static_cast<size_t>(y)*sw+x]=luminance(p[0],p[1],p[2])/255.f;
        }
        auto sample=[](const float* line,int count,int stride,float position) {
            position=std::clamp(position,0.f,static_cast<float>(count-1));
            const int i=static_cast<int>(position);const float f=position-i;
            return line[i*stride]*(1-f)+line[std::min(i+1,count-1)*stride]*f;
        };
        for(int y=0;y<sh;++y)for(int x=0;x<sw;++x) {
            const auto* row=lum.data()+static_cast<size_t>(y)*sw;
            horizontal[static_cast<size_t>(y)*sw+x]=(sample(row,sw,1,x-radius)+row[x]*2.f+sample(row,sw,1,x+radius))*.25f;
        }
        for(int y=0;y<sh;++y)for(int x=0;x<sw;++x) {
            const size_t i=static_cast<size_t>(y)*sw+x;
            highpass[i]=lum[i]-(sample(horizontal.data()+x,sh,sw,y-radius)+horizontal[i]*2.f+sample(horizontal.data()+x,sh,sw,y+radius))*.25f;
        }
        sharpMaps.push_back({radius,std::move(highpass)});return sharpMaps.back().values.data();
    }
    void ensure(const ImageView& src, bool local, bool haze) {
        if ((!local || !fine.empty()) && (!haze || !dark.empty())) return;
        const float scale=std::min(1.f,1024.f/std::max(src.width,src.height));
        w=std::max(1,static_cast<int>(std::round(src.width*scale)));
        h=std::max(1,static_cast<int>(std::round(src.height*scale)));
        std::vector<float> lum(static_cast<size_t>(w)*h), mins(lum.size());
        std::vector<uint8_t> rgb(lum.size()*3);
        for (int y=0;y<h;++y) for(int x=0;x<w;++x) {
            // Area average, matching session downsampling rather than aliasing fine patterns.
            const int x0=x*src.width/w, x1=std::max(x0+1,(x+1)*src.width/w);
            const int y0=y*src.height/h, y1=std::max(y0+1,(y+1)*src.height/h);
            float c[3]{};
            for(int sy=y0;sy<y1;++sy) for(int sx=x0;sx<x1;++sx) {
                const auto* p=src.pixel(sx,sy); for(int k=0;k<3;++k)c[k]+=p[k];
            }
            const float n=static_cast<float>((x1-x0)*(y1-y0))*255.f;
            for(float& v:c)v/=n;
            const size_t i=static_cast<size_t>(y)*w+x;
            lum[i]=luminance(c[0],c[1],c[2]); mins[i]=std::min({c[0],c[1],c[2]});
            for(int k=0;k<3;++k)rgb[i*3+k]=static_cast<uint8_t>(clamp255(c[k]*255.f));
        }
        if(local && fine.empty()) {
            fine=boxBlur(lum,w,h,1);
            medium=boxBlur(boxBlur(lum,w,h,2),w,h,2);
            coarse=boxBlur(boxBlur(lum,w,h,8),w,h,8);
        }
        if(haze && dark.empty()) {
            // Dark-channel prior; atmospheric light is the brightest pixel among the
            // top 0.1% dark-channel candidates, avoiding a bright foreground object.
            auto rawDark=minimumFilter(mins,w,h,std::max(1,std::max(w,h)*7/1024));
            int histogram[256]{};
            for(float v:rawDark)++histogram[clamp255(v*255.f)];
            int target=std::max(1,static_cast<int>(rawDark.size()/1000)), total=0, threshold=255;
            while(threshold>0 && total<target)total+=histogram[threshold--];
            float brightest=-1.f;
            for(size_t i=0;i<rawDark.size();++i)if(rawDark[i]*255.f>=threshold && lum[i]>brightest) {
                brightest=lum[i]; for(int k=0;k<3;++k)air[k]=std::clamp(rgb[i*3+k]/255.f,.5f,.98f);
            }
            for(size_t i=0;i<mins.size();++i)mins[i]=std::min({rgb[i*3]/(255.f*air[0]),
                rgb[i*3+1]/(255.f*air[1]),rgb[i*3+2]/(255.f*air[2])});
            dark=boxBlur(minimumFilter(mins,w,h,std::max(1,std::max(w,h)*7/1024)),w,h,2);
        }
    }
    float sample(const std::vector<float>& map, int x, int y, int width, int height) const {
        if(map.empty())return 0.f;
        if(width==w && height==h)return map[static_cast<size_t>(y)*w+x];
        const float fx=std::clamp((x+.5f)*w/width-.5f,0.f,static_cast<float>(w-1));
        const float fy=std::clamp((y+.5f)*h/height-.5f,0.f,static_cast<float>(h-1));
        const int ix=static_cast<int>(fx), iy=static_cast<int>(fy), jx=std::min(ix+1,w-1), jy=std::min(iy+1,h-1);
        const float dx=fx-ix,dy=fy-iy;
        return (map[static_cast<size_t>(iy)*w+ix]*(1-dx)+map[static_cast<size_t>(iy)*w+jx]*dx)*(1-dy)+
            (map[static_cast<size_t>(jy)*w+ix]*(1-dx)+map[static_cast<size_t>(jy)*w+jx]*dx)*dy;
    }
};

/** A few original rows suffice for in-place masked sharpening; no full-size image copy. */
struct ScanSource {
    ImageView src;
    int margin=0, loaded=-1;
    std::vector<uint8_t> rows;
    ScanSource(const ImageView& s, int m, int firstRow=0):src(s),margin(m),loaded(std::max(0,firstRow-m)-1) {
        if(m>0)rows.resize(static_cast<size_t>(2*m+1)*s.width*3);
    }
    void advance(int y) {
        if(margin==0)return;
        const int end=std::min(src.height-1,y+margin);
        while(loaded<end) {
            ++loaded;
            auto* dst=rows.data()+static_cast<size_t>(loaded%(2*margin+1))*src.width*3;
            for(int x=0;x<src.width;++x)std::copy_n(src.pixel(x,loaded),3,dst+x*3);
        }
    }
    float lum(int x,int y) const {
        x=std::clamp(x,0,src.width-1);y=std::clamp(y,0,src.height-1);
        const auto* p=margin>0?rows.data()+(static_cast<size_t>(y%(2*margin+1))*src.width+x)*3:src.pixel(x,y);
        return luminance(p[0],p[1],p[2])/255.f;
    }
    float sample(float x,float y) const {
        x=std::clamp(x,0.f,static_cast<float>(src.width-1));y=std::clamp(y,0.f,static_cast<float>(src.height-1));
        const int ix=static_cast<int>(x),iy=static_cast<int>(y);const float dx=x-ix,dy=y-iy;
        return (lum(ix,iy)*(1-dx)+lum(ix+1,iy)*dx)*(1-dy)+(lum(ix,iy+1)*(1-dx)+lum(ix+1,iy+1)*dx)*dy;
    }
    float sharp(int x,int y,float radius,float detail,float masking) const {
        const float c=lum(x,y);
        float blurred=c*4.f;
        for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx) {
            if(dx==0 && dy==0)continue;
            blurred+=sample(x+dx*radius,y+dy*radius)*(dx==0||dy==0?2.f:1.f);
        }
        float hp=c-blurred/16.f;
        // Low Detail suppresses sub-threshold noise; Masking limits sharpening to edges.
        hp*=smooth(.0015f*(1-detail),.018f*(1-detail)+.001f,std::fabs(hp));
        const float gate=masking<=0?1.f:smooth(masking*.035f,masking*.09f+.004f,std::fabs(hp));
        return hp*gate;
    }
};

inline float hashNoise(int x,int y,uint32_t seed) {
    uint32_t v=static_cast<uint32_t>(x)*0x9E3779B9u^static_cast<uint32_t>(y)*0x85EBCA6Bu^seed;
    v^=v>>16;v*=0x7FEB352Du;v^=v>>15;v*=0x846CA68Bu;v^=v>>16;
    return (v&0xffffu)/32767.5f-1.f;
}
inline float grainNoise(float x,float y,float roughness) {
    const int ix=static_cast<int>(std::floor(x)),iy=static_cast<int>(std::floor(y));
    float fx=x-ix,fy=y-iy; fx=fx*fx*(3-2*fx);fy=fy*fy*(3-2*fy);
    const float soft=(hashNoise(ix,iy,17)*(1-fx)+hashNoise(ix+1,iy,17)*fx)*(1-fy)+
        (hashNoise(ix,iy+1,17)*(1-fx)+hashNoise(ix+1,iy+1,17)*fx)*fy;
    return soft*(1-roughness*.5f)+hashNoise(static_cast<int>(x*2),static_cast<int>(y*2),51)*roughness*.5f;
}

inline void processAdvanced(const RenderData& d, uint8_t& R,uint8_t& G,uint8_t& B,
    int x,int y,const ScanSource& source,const Analysis& analysis,float sourceScale=1.f) {
    if(!d.hasEffects){processPixel(d,R,G,B);return;}
    const float* e=d.effects;
    const int w=source.src.width,h=source.src.height;
    float r=R/255.f,g=G/255.f,b=B/255.f;
    const float originalL=luminance(r,g,b);
    if(e[Dehaze]!=0.f) {
        if(e[Dehaze]>0.f) {
            const float dark=analysis.sample(analysis.dark,x,y,w,h);
            const float t=std::max(.25f,1.f-.85f*e[Dehaze]*dark);
            r=(r-analysis.air[0])/t+analysis.air[0];g=(g-analysis.air[1])/t+analysis.air[1];b=(b-analysis.air[2])/t+analysis.air[2];
        } else {
            const float fog=-e[Dehaze]*.55f;
            r=r*(1-fog)+analysis.air[0]*fog;g=g*(1-fog)+analysis.air[1]*fog;b=b*(1-fog)+analysis.air[2]*fog;
        }
    }
    float delta=0.f;
    if(e[Texture]!=0.f)delta+=e[Texture]*1.5f*(analysis.sample(analysis.fine,x,y,w,h)-analysis.sample(analysis.medium,x,y,w,h));
    if(e[Clarity]!=0.f) {
        const float hp=originalL-analysis.sample(analysis.coarse,x,y,w,h);
        delta+=e[Clarity]*.8f*hp/(1.f+8.f*std::fabs(hp))*4.f*originalL*(1-originalL);
    }
    if(e[Sharpening]>0.f) {
        float hp;
        if(d.sharpMap) {
            hp=d.sharpMap[static_cast<size_t>(y)*w+x];
            hp*=smooth(.0015f*(1-e[SharpenDetail]),.018f*(1-e[SharpenDetail])+.001f,std::fabs(hp));
            if(e[SharpenMasking]>0)hp*=smooth(e[SharpenMasking]*.035f,e[SharpenMasking]*.09f+.004f,std::fabs(hp));
        } else hp=source.sharp(x,y,e[SharpenRadius]*sourceScale,e[SharpenDetail],e[SharpenMasking]);
        delta+=e[Sharpening]*2.f*hp;
    }
    R=static_cast<uint8_t>(clamp255((r+delta)*255.f));G=static_cast<uint8_t>(clamp255((g+delta)*255.f));B=static_cast<uint8_t>(clamp255((b+delta)*255.f));
    processPixel(d,R,G,B);
    r=R/255.f;g=G/255.f;b=B/255.f;
    if(e[Vibrance]!=0.f) {
        const float maxC=std::max({r,g,b}),minC=std::min({r,g,b}),sat=maxC-minC;
        const float l=luminance(r,g,b);
        // Skin-like warm hues receive gentler positive saturation.
        const float skin=(r>g && g>b)? .5f : 1.f;
        const float gain=1.f+e[Vibrance]*(1.f-sat)*(e[Vibrance]>0?skin:1.f);
        r=l+(r-l)*gain;g=l+(g-l)*gain;b=l+(b-l)*gain;
    }
    if(e[BlackWhite]>.5f) {
        float hue,s,l;rgbToHsl(clamp01(r),clamp01(g),clamp01(b),hue,s,l);
        float mix=0,weights=0;
        for(int i=0;i<8;++i) {
            const float weight=std::max(0.f,1.f-circDist(hue,HSL_CENTERS[i])/45.f);
            mix+=weight*e[BwMix+i];weights+=weight;
        }
        r=g=b=clamp01(luminance(r,g,b)+(weights>0?mix/weights:0.f)*s*.35f);
    }
    if(d.hasGrade) {
        const float l=clamp01(luminance(r,g,b)),tone=clamp01(l-e[GradeBalance]*.25f);
        const float width=.12f+e[GradeBlending]*.36f;
        float weights[4]={1.f-smooth(.33f-width,.33f+width,tone),0.f,smooth(.67f-width,.67f+width,tone),1.f};
        weights[1]=std::max(0.f,1.f-weights[0]-weights[2]);
        const float sum=weights[0]+weights[1]+weights[2];
        for(int i=0;i<3;++i)weights[i]/=sum;
        for(int i=0;i<4;++i) {
            const float lift=e[Grades+i*3+2]*.25f;
            const float protection=4.f*l*(1.f-l);
            r+=weights[i]*(d.grade[i][0]*protection+lift);
            g+=weights[i]*(d.grade[i][1]*protection+lift);
            b+=weights[i]*(d.grade[i][2]*protection+lift);
        }
    }
    if(e[Vignette]!=0.f) {
        float nx=(x+.5f-w*.5f)/(w*.5f),ny=(y+.5f-h*.5f)/(h*.5f);
        // At +100, use a physical circle even for non-square images.
        const float round=std::max(0.f,e[VignetteRoundness]);
        nx*=1.f+round*(w/static_cast<float>(std::min(w,h))-1.f);
        ny*=1.f+round*(h/static_cast<float>(std::min(w,h))-1.f);
        const float ellipse=std::sqrt(nx*nx+ny*ny);
        const float squareness=-std::min(0.f,e[VignetteRoundness]);
        const float distance=ellipse*(1.f-squareness)+std::max(std::fabs(nx),std::fabs(ny))*squareness;
        const float start=.1f+e[VignetteMidpoint]*.85f;
        float weight=smooth(start,start+.03f+e[VignetteFeather]*.9f,distance);
        const float l=clamp01(luminance(r,g,b));
        if(e[Vignette]<0)weight*=1.f-e[VignetteHighlights]*smooth(.55f,1.f,l);
        const float amount=e[Vignette]*weight*.8f;
        if(amount<0){r*=1+amount;g*=1+amount;b*=1+amount;}
        else {r+=(1-r)*amount;g+=(1-g)*amount;b+=(1-b)*amount;}
    }
    if(e[Grain]>0.f) {
        const float scale=1024.f/std::max(w,h),size=.45f+e[GrainSize]*2.8f;
        const float noise=grainNoise((x+.5f)*scale/size,(y+.5f)*scale/size,e[GrainRoughness]);
        const float l=clamp01(luminance(r,g,b));
        const float n=noise*e[Grain]*.16f*(.3f+.7f*4.f*l*(1-l));
        r+=n;g+=n;b+=n;
    }
    R=static_cast<uint8_t>(clamp255(r*255.f));G=static_cast<uint8_t>(clamp255(g*255.f));B=static_cast<uint8_t>(clamp255(b*255.f));
}

inline bool needsLocal(const RenderData& d){return d.effects[Texture]!=0.f||d.effects[Clarity]!=0.f;}
inline bool needsHaze(const RenderData& d){return d.effects[Dehaze]!=0.f;}

// Use at most half the available cores, leaving room for gestures and UI composition.
// Spatial caches are prepared before dispatch and remain read-only in each worker.
template<class F> inline void forRows(int width,int height,bool parallel,F work) {
    const int workers=parallel && static_cast<int64_t>(width)*height>=128*1024?
        std::clamp(static_cast<int>(std::thread::hardware_concurrency()/2),1,4):1;
    if(workers==1){work(0,height);return;}
    std::vector<std::thread> threads;threads.reserve(workers-1);
    std::exception_ptr error;std::mutex errorMutex;
    auto run=[&](int i) {
        try{work(i*height/workers,(i+1)*height/workers);}
        catch(...){std::lock_guard<std::mutex> lock(errorMutex);if(!error)error=std::current_exception();}
    };
    try {for(int i=1;i<workers;++i)threads.emplace_back(run,i);run(0);}
    catch(...){for(auto& thread:threads)thread.join();throw;}
    for(auto& thread:threads)thread.join();
    if(error)std::rethrow_exception(error);
}

inline void renderImage(const ImageView& src,ImageView dst,RenderData d,Analysis& analysis,float sourceScale=1.f) {
    analysis.ensure(src,needsLocal(d),needsHaze(d));
    analysis.retainSharpening(d.effects[Sharpening]>0?std::vector<float>{d.effects[SharpenRadius]*sourceScale}:std::vector<float>{});
    if(d.effects[Sharpening]>0)d.sharpMap=analysis.sharpening(src,d.effects[SharpenRadius]*sourceScale);
    const int margin=d.effects[Sharpening]>0 && !d.sharpMap?static_cast<int>(std::ceil(d.effects[SharpenRadius]*sourceScale))+1:0;
    const bool neutral=isNeutralData(d);
    forRows(src.width,src.height,src.data!=dst.data,[&](int begin,int end) {
    ScanSource source(src,margin,begin);
    for(int y=begin;y<end;++y) {
        source.advance(y);
        auto* row=dst.data+static_cast<size_t>(y)*dst.stride;
        for(int x=0;x<src.width;++x) {
            const auto* in=src.pixel(x,y);uint8_t r=in[0],g=in[1],b=in[2];
            if(!neutral)processAdvanced(d,r,g,b,x,y,source,analysis,sourceScale);
            auto* out=row+x*dst.channels;out[0]=r;out[1]=g;out[2]=b;
            if(dst.channels==4)out[3]=255;
        }
    }
    });
}

inline float maskAlpha(const uint8_t* mask,int mw,int mh,int x,int y,int w,int h) {
    const float mx=std::clamp((x+.5f)*mw/w-.5f,0.f,static_cast<float>(mw-1));
    const float my=std::clamp((y+.5f)*mh/h-.5f,0.f,static_cast<float>(mh-1));
    const int ix=static_cast<int>(mx),iy=static_cast<int>(my),jx=std::min(ix+1,mw-1),jy=std::min(iy+1,mh-1);
    const float fx=mx-ix,fy=my-iy;
    return ((mask[iy*mw+ix]*(1-fx)+mask[iy*mw+jx]*fx)*(1-fy)+
        (mask[jy*mw+ix]*(1-fx)+mask[jy*mw+jx]*fx)*fy)/255.f;
}

inline void renderMasks(ImageView bitmap,std::vector<RenderData> layers,const uint8_t* maps,
    int mw,int mh,Analysis& analysis,float sourceScale=1.f) {
    bool local=false,haze=false;int margin=0;
    std::vector<float> radii;
    for(const auto& d:layers)if(d.effects[Sharpening]>0 && radii.size()<4 &&
        std::find(radii.begin(),radii.end(),d.effects[SharpenRadius]*sourceScale)==radii.end())radii.push_back(d.effects[SharpenRadius]*sourceScale);
    analysis.retainSharpening(radii);
    for(auto& d:layers) {
        local|=needsLocal(d);haze|=needsHaze(d);
        if(d.effects[Sharpening]>0) {
            d.sharpMap=analysis.sharpening(bitmap,d.effects[SharpenRadius]*sourceScale);
            if(!d.sharpMap)margin=std::max(margin,static_cast<int>(std::ceil(d.effects[SharpenRadius]*sourceScale))+1);
        }
    }
    analysis.ensure(bitmap,local,haze);
    // Uncached full-size masked sharpening needs original neighbor rows; keep it serial.
    forRows(bitmap.width,bitmap.height,margin==0,[&](int begin,int end) {
    ScanSource source(bitmap,margin,begin);
    for(int y=begin;y<end;++y) {
        source.advance(y);
        auto* row=bitmap.data+static_cast<size_t>(y)*bitmap.stride;
        for(int x=0;x<bitmap.width;++x) {
            auto* p=row+x*bitmap.channels;
            for(size_t i=0;i<layers.size();++i) {
                const float a=maskAlpha(maps+i*static_cast<size_t>(mw)*mh,mw,mh,x,y,bitmap.width,bitmap.height);
                if(a<=0)continue;
                uint8_t r=p[0],g=p[1],b=p[2];
                processAdvanced(layers[i],r,g,b,x,y,source,analysis,sourceScale);
                p[0]=static_cast<uint8_t>(clamp255(p[0]+(r-p[0])*a));
                p[1]=static_cast<uint8_t>(clamp255(p[1]+(g-p[1])*a));
                p[2]=static_cast<uint8_t>(clamp255(p[2]+(b-p[2])*a));
            }
        }
    }
    });
}

} // namespace dcim
