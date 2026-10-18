#include "../app/src/main/cpp/tone_engine.h"
#include <cassert>
#include <chrono>
#include <fstream>
#include <iostream>
#include <string>
using namespace dcim;

RenderData defaults() {
    RenderData d;
    d.m[0]=d.m[6]=d.m[12]=d.m[18]=1.f;
    for(int i=0;i<768;++i)d.lut[i]=static_cast<float>(i%256);
    d.effects[VignetteMidpoint]=d.effects[VignetteFeather]=.5f;
    d.effects[GrainSize]=.25f;d.effects[GrainRoughness]=.5f;
    d.effects[SharpenRadius]=1.f;d.effects[SharpenDetail]=.25f;d.effects[GradeBlending]=.5f;
    prepare(d);return d;
}
std::vector<uint8_t> fixture(int w,int h) {
    std::vector<uint8_t> data(static_cast<size_t>(w)*h*3);
    for(int y=0;y<h;++y)for(int x=0;x<w;++x) {
        const float texture=std::sin(x*.9f)*std::cos(y*.8f)*17.f;
        auto* p=data.data()+(static_cast<size_t>(y)*w+x)*3;
        p[0]=static_cast<uint8_t>(clamp255(55.f+x*140.f/w+texture));
        p[1]=static_cast<uint8_t>(clamp255(65.f+y*110.f/h+texture));
        p[2]=static_cast<uint8_t>(clamp255(85.f+(x>w/2?40.f:0.f)+texture));
    }return data;
}
std::vector<uint8_t> render(std::vector<uint8_t>& src,int w,int h,RenderData d) {
    prepare(d);Analysis a;std::vector<uint8_t> out(src.size());
    renderImage({src.data(),w,h,w*3,3},{out.data(),w,h,w*3,3},d,a);return out;
}
double difference(const std::vector<uint8_t>& a,const std::vector<uint8_t>& b) {
    double sum=0;for(size_t i=0;i<a.size();++i)sum+=std::abs(static_cast<int>(a[i])-b[i]);return sum/a.size();
}
void checks() {
    const int w=127,h=91;auto src=fixture(w,h);auto d=defaults();
    assert(render(src,w,h,d)==src);
    // RGB tables must affect only their channel, and masks must use identical curves.
    auto curve=d;
    for(int i=0;i<256;++i)curve.lut[i]=std::min(255.f,i*1.3f);
    assert(!isNeutralData(curve));
    auto curved=render(src,w,h,curve);
    assert(difference(curved,src)>.1);
    for(size_t i=0;i<src.size();i+=3) {
        assert(curved[i+1]==src[i+1] && curved[i+2]==src[i+2]);
    }
    auto maskedCurve=src;Analysis curveAnalysis;std::vector<uint8_t> curveCoverage(w*h,255);
    renderMasks({maskedCurve.data(),w,h,w*3,3},{curve},curveCoverage.data(),w,h,curveAnalysis);
    assert(maskedCurve==curved);
    const int indices[]={Vibrance,Texture,Clarity,Dehaze,Vignette,Grain,BlackWhite,Sharpening,Grades+1};
    for(int index:indices) {
        auto p=d;p.effects[index]=.85f;prepare(p);
        assert(!isNeutralData(p));auto out=render(src,w,h,p);
        assert(difference(out,src)>.1);
        // Every effect must agree between a global edit and a fully covering mask.
        auto masked=src;Analysis analysis;std::vector<uint8_t> coverage(w*h,255);
        renderMasks({masked.data(),w,h,w*3,3},{p},coverage.data(),w,h,analysis);
        assert(out==masked);
        std::fill(coverage.begin(),coverage.end(),0);masked=src;analysis.clear();
        renderMasks({masked.data(),w,h,w*3,3},{p},coverage.data(),w,h,analysis);
        assert(masked==src);
        for(int y=0;y<h;++y)for(int x=0;x<w;++x)coverage[y*w+x]=x<w/2?255:0;
        masked=src;analysis.clear();renderMasks({masked.data(),w,h,w*3,3},{p},coverage.data(),w,h,analysis);
        for(int y=0;y<h;++y)for(int x=0;x<w;++x)for(int c=0;c<3;++c) {
            const int i=(y*w+x)*3+c;assert(masked[i]==(x<w/2?out[i]:src[i]));
        }
    }
    auto bw=d;bw.effects[BlackWhite]=1;auto bwOut=render(src,w,h,bw);
    for(size_t i=0;i<bwOut.size();i+=3)assert(bwOut[i]==bwOut[i+1]&&bwOut[i]==bwOut[i+2]);
    bw.effects[BwMix]=1;assert(render(src,w,h,bw)!=bwOut);
    auto grain=d;grain.effects[Grain]=.8f;assert(render(src,w,h,grain)==render(src,w,h,grain));
    auto sharp=d;sharp.effects[Sharpening]=1;auto sharpOut=render(src,w,h,sharp);
    sharp.effects[SharpenRadius]=3;assert(render(src,w,h,sharp)!=sharpOut);
    sharp.effects[SharpenMasking]=1;assert(render(src,w,h,sharp)!=sharpOut);
    // Combined effects + feathered masking, exercising original-row preservation.
    auto all=d;for(int i:{Vibrance,Texture,Clarity,Dehaze,Grain,Sharpening})all.effects[i]=.6f;
    all.effects[Vignette]=-.7f;all.effects[Grades+1]=.3f;prepare(all);
    auto out=render(src,w,h,all),masked=src;
    std::vector<uint8_t> coverage(w*h,128);Analysis a;
    renderMasks({masked.data(),w,h,w*3,3},{all},coverage.data(),w,h,a);
    for(size_t i=0;i<src.size();++i)assert(std::abs(masked[i]-clamp255(src[i]+(out[i]-src[i])*128.f/255.f))<=1);
    auto partial=d;partial.effects[VignetteFeather]=1;partial.effects[SharpenRadius]=3;prepare(partial);
    assert(isNeutralData(partial)&&render(src,w,h,partial)==src);
    // Padded bitmap rows must remain untouched; tiny/degenerate image dimensions are valid.
    for(auto dims:{std::pair<int,int>{1,1},{1,17},{19,1},{7,3}}) {
        const int tw=dims.first,th=dims.second;auto tiny=fixture(tw,th);const int stride=tw*4+12;
        std::vector<uint8_t> padded(static_cast<size_t>(stride)*th,123);
        Analysis small;renderImage({tiny.data(),tw,th,tw*3,3},{padded.data(),tw,th,stride,4},all,small);
        for(int y=0;y<th;++y)for(int x=tw*4;x<stride;++x)assert(padded[y*stride+x]==123);
    }
    // Warm analysis does not rebuild maps when only slider values change.
    Analysis cached;ImageView view{src.data(),w,h,w*3,3};cached.ensure(view,true,true);
    auto* fine=cached.fine.data();auto* dark=cached.dark.data();cached.ensure(view,true,true);
    assert(cached.fine.data()==fine&&cached.dark.data()==dark);
    std::vector<RenderData> stacked;
    std::vector<uint8_t> maps(static_cast<size_t>(w)*h*6,128);
    for(int i=0;i<6;++i){auto layer=d;layer.effects[Sharpening]=.5f;layer.effects[SharpenRadius]=.5f+i*.5f;prepare(layer);stacked.push_back(layer);}
    masked=src;Analysis many;renderMasks({masked.data(),w,h,w*3,3},stacked,maps.data(),w,h,many);
    assert(many.sharpMaps.size()<=4);
    // Full-size path does not allocate a full-image sharpening map. Original row
    // preservation must agree with out-of-place rendering, even at row-band edges.
    const int fw=1283,fh=827;auto full=fixture(fw,fh);auto fullSharp=defaults();
    fullSharp.effects[Sharpening]=.8f;fullSharp.effects[SharpenRadius]=3.f;prepare(fullSharp);
    auto fullOut=render(full,fw,fh,fullSharp),fullMasked=full;
    std::vector<uint8_t> fullMap(static_cast<size_t>(fw)*fh,255);Analysis fullAnalysis;
    renderMasks({fullMasked.data(),fw,fh,fw*3,3},{fullSharp},fullMap.data(),fw,fh,fullAnalysis);
    assert(fullOut==fullMasked && fullAnalysis.sharpMaps.empty());
    const uint8_t lowMap[]={0,255,0,255};
    assert(maskAlpha(lowMap,2,2,0,0,w,h)==0.f && maskAlpha(lowMap,2,2,w-1,h-1,w,h)==1.f);
    assert(std::fabs(maskAlpha(lowMap,2,2,w/2,h/2,w,h)-.5f)<.01f);
    std::cout<<"PASS: 9 effects, all/empty/partial masks, grayscale mixer, stable grain, sharpening, strides and tiny images\n";
    auto large=fixture(1024,683);std::vector<uint8_t> largeOut(large.size());Analysis warmed;
    ImageView lv{large.data(),1024,683,1024*3,3},ov{largeOut.data(),1024,683,1024*3,3};
    renderImage(lv,ov,all,warmed);
    const auto start=std::chrono::steady_clock::now();
    for(int i=0;i<12;++i)renderImage(lv,ov,all,warmed);
    std::cout<<"Desktop 1024px warm preview, all effects: "<<std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count()/12<<" ms/frame\n";
    for(int index:indices) {
        auto single=defaults();single.effects[index]=.7f;prepare(single);Analysis cache;
        renderImage(lv,ov,single,cache);const auto began=std::chrono::steady_clock::now();
        for(int i=0;i<4;++i)renderImage(lv,ov,single,cache);
        std::cout<<"Effect "<<index<<": "<<std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-began).count()/4<<" ms/frame\n";
    }
}
int main(int argc,char** argv) {
    checks();
    if(argc!=3)return 0;
    std::ifstream in(argv[1],std::ios::binary);std::string magic;int w,h,max;
    in>>magic>>w>>h>>max;in.get();assert(magic=="P6"&&max==255);
    std::vector<uint8_t> src(static_cast<size_t>(w)*h*3);in.read(reinterpret_cast<char*>(src.data()),src.size());
    const char* names[]={"original","vibrance","texture","clarity","dehaze","vignette","grain","grading","blackwhite","sharpening"};
    const int indices[]={-1,Vibrance,Texture,Clarity,Dehaze,Vignette,Grain,Grades+1,BlackWhite,Sharpening};
    for(int i=0;i<10;++i) {
        auto p=defaults();if(i>0)p.effects[indices[i]]=i==5?-.7f:.7f;
        if(i==7){p.effects[Grades]=.6f;p.effects[Grades+7]=.3f;p.effects[Grades+6]=.08f;}
        auto out=render(src,w,h,p);std::ofstream f(std::string(argv[2])+"/"+names[i]+".ppm",std::ios::binary);
        f<<"P6\n"<<w<<" "<<h<<"\n255\n";f.write(reinterpret_cast<const char*>(out.data()),out.size());
    }
}
