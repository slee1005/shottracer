#ifndef SHOTTRACER_DESKTOP_CAMERA_HPP
#define SHOTTRACER_DESKTOP_CAMERA_HPP

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <utility>
#include <vector>

namespace st_desktop {

// Reference pixels -> frame pixels. Keeping the complete similarity transform is
// essential: an offset alone cannot put a tracer back into a rotated/zoomed frame.
struct Transform {
    double a = 1, b = 0, x = 0, y = 0;
    std::pair<double, double> apply(double u, double v) const {
        return {a*u - b*v + x, b*u + a*v + y};
    }
    Transform inverse() const {
        const double d = a*a + b*b;
        return {a/d, -b/d, (-a*x-b*y)/d, (b*x-a*y)/d};
    }
    Transform after(const Transform &other) const {
        const auto p = apply(other.x, other.y);
        return {a*other.a-b*other.b, b*other.a+a*other.b, p.first, p.second};
    }
};

struct Image {
    int w = 0, h = 0;
    std::vector<float> pixels;
    Image() = default;
    Image(int width, int height) : w(width), h(height), pixels(size_t(w)*h) {}
    float at(int x, int y) const {
        return pixels[size_t(std::clamp(y,0,h-1))*w+std::clamp(x,0,w-1)];
    }
    double sample(double x, double y) const {
        x=std::clamp(x,0.0,double(w-1)); y=std::clamp(y,0.0,double(h-1));
        const int ix=int(x), iy=int(y);
        const double fx=x-ix, fy=y-iy;
        return (1-fy)*((1-fx)*at(ix,iy)+fx*at(ix+1,iy)) +
            fy*((1-fx)*at(ix,iy+1)+fx*at(ix+1,iy+1));
    }
};

inline Image blur(const Image &image) {
    Image out(image.w,image.h), horizontal(image.w,image.h);
    for(int y=0;y<image.h;++y) for(int x=0;x<image.w;++x)
        horizontal.pixels[size_t(y)*image.w+x]=(image.at(x-1,y)+2*image.at(x,y)+image.at(x+1,y))*.25f;
    for(int y=0;y<image.h;++y) for(int x=0;x<image.w;++x)
        out.pixels[size_t(y)*image.w+x]=(horizontal.at(x,y-1)+2*horizontal.at(x,y)+horizontal.at(x,y+1))*.25f;
    return out;
}

inline std::vector<Image> pyramid(const Image &image) {
    std::vector<Image> levels{image};
    for(int level=1;level<=3 && levels.back().w>=32 && levels.back().h>=32;++level) {
        const auto smooth=blur(levels.back());
        Image small((smooth.w+1)/2,(smooth.h+1)/2);
        for(int y=0;y<small.h;++y) for(int x=0;x<small.w;++x)
            small.pixels[size_t(y)*small.w+x]=smooth.at(2*x,2*y);
        levels.push_back(std::move(small));
    }
    return levels;
}

struct Corner { double x,y,score; };
inline std::vector<Corner> corners(const Image &image) {
    const int w=image.w,h=image.h,pitch=w+1;
    // Integral gradient products make the same 7x7 corner window inexpensive.
    std::array<std::vector<double>,3> sums;
    for(auto &s:sums) s.resize(size_t(pitch)*(h+1));
    for(int y=0;y<h;++y) {
        std::array<double,3> row{};
        for(int x=0;x<w;++x) {
            const double gx=(image.at(x+1,y)-image.at(x-1,y))*.5;
            const double gy=(image.at(x,y+1)-image.at(x,y-1))*.5;
            const std::array<double,3> value{gx*gx,gx*gy,gy*gy};
            for(int k=0;k<3;++k) {
                row[k]+=value[k];
                sums[k][size_t(y+1)*pitch+x+1]=sums[k][size_t(y)*pitch+x+1]+row[k];
            }
        }
    }
    std::vector<Corner> candidates;
    double maximum=0;
    for(int y=8;y<h-8;++y) for(int x=8;x<w-8;++x) {
        std::array<double,3> g{};
        for(int k=0;k<3;++k) {
            const auto &s=sums[k];
            g[k]=s[size_t(y+4)*pitch+x+4]-s[size_t(y-3)*pitch+x+4]
                -s[size_t(y+4)*pitch+x-3]+s[size_t(y-3)*pitch+x-3];
        }
        const double score=(g[0]+g[2]-std::hypot(g[0]-g[2],2*g[1]))*.5;
        if(score>1e-6) { maximum=std::max(maximum,score); candidates.push_back({double(x),double(y),score}); }
    }
    std::stable_sort(candidates.begin(),candidates.end(),[](const Corner &a,const Corner &b){return a.score>b.score;});
    std::vector<Corner> selected;
    for(const auto &c:candidates) {
        if(c.score<.01*maximum || selected.size()>=600) break;
        bool near=false;
        for(const auto &p:selected) if(std::hypot(c.x-p.x,c.y-p.y)<8) { near=true; break; }
        if(!near) selected.push_back(c);
    }
    return selected;
}

inline bool optical_flow(const std::vector<Image> &previous,const std::vector<Image> &current,
                         double x,double y,double &u,double &v) {
    double dx=0,dy=0;
    const int last=int(std::min(previous.size(),current.size()))-1;
    for(int level=last;level>=0;--level) {
        if(level!=last) { dx*=2; dy*=2; }
        const double factor=std::ldexp(1.0,-level),px=x*factor,py=y*factor;
        const auto &a=previous[size_t(level)],&b=current[size_t(level)];
        double aa=0,ab=0,bb=0;
        std::array<double,441> intensity{},gx{},gy{};
        int i=0;
        for(int yy=-10;yy<=10;++yy) for(int xx=-10;xx<=10;++xx,++i) {
            const double sx=px+xx,sy=py+yy;
            intensity[i]=a.sample(sx,sy);
            gx[i]=(a.sample(sx+1,sy)-a.sample(sx-1,sy))*.5;
            gy[i]=(a.sample(sx,sy+1)-a.sample(sx,sy-1))*.5;
            aa+=gx[i]*gx[i]; ab+=gx[i]*gy[i]; bb+=gy[i]*gy[i];
        }
        const double determinant=aa*bb-ab*ab;
        if(determinant<1e-6) { if(level==0) return false; else continue; }
        for(int iteration=0;iteration<20;++iteration) {
            double ax=0,ay=0; i=0;
            for(int yy=-10;yy<=10;++yy) for(int xx=-10;xx<=10;++xx,++i) {
                const double difference=intensity[i]-b.sample(px+dx+xx,py+dy+yy);
                ax+=gx[i]*difference; ay+=gy[i]*difference;
            }
            const double step_x=(bb*ax-ab*ay)/determinant,step_y=(aa*ay-ab*ax)/determinant;
            if(!std::isfinite(step_x) || !std::isfinite(step_y) || std::hypot(step_x,step_y)>12) return false;
            dx+=step_x; dy+=step_y;
            if(std::hypot(step_x,step_y)<.01) break;
        }
        if(px+dx<0 || py+dy<0 || px+dx>=b.w || py+dy>=b.h) return false;
    }
    u=x+dx; v=y+dy;
    return std::isfinite(u) && std::isfinite(v);
}

struct Match { double x,y,u,v; };
inline Transform fit_similarity(const std::vector<Match> &matches,const std::vector<size_t> &indices) {
    double x=0,y=0,u=0,v=0;
    for(size_t i:indices) { x+=matches[i].x; y+=matches[i].y; u+=matches[i].u; v+=matches[i].v; }
    const double n=double(indices.size()); x/=n; y/=n; u/=n; v/=n;
    double denominator=0,a=0,b=0;
    for(size_t i:indices) {
        const auto &p=matches[i]; const double px=p.x-x,py=p.y-y,qx=p.u-u,qy=p.v-v;
        denominator+=px*px+py*py; a+=px*qx+py*qy; b+=px*qy-py*qx;
    }
    if(denominator<1e-9) return {};
    a/=denominator; b/=denominator;
    return {a,b,u-a*x+b*y,v-b*x-a*y};
}

inline Transform camera_motion(const std::vector<Image> &previous,const std::vector<Image> &current) {
    const auto points=corners(previous[0]);
    if(points.size()<12) return {};
    std::vector<Match> matches;
    for(const auto &p:points) {
        double u=0,v=0;
        if(optical_flow(previous,current,p.x,p.y,u,v)) matches.push_back({p.x,p.y,u,v});
    }
    if(matches.size()<12) return {};
    std::vector<size_t> best;
    uint32_t random=0x713ac5U;
    int iterations=3000;
    for(int i=0;i<iterations;++i) {
        random=random*1664525U+1013904223U; const size_t a=random%matches.size();
        random=random*1664525U+1013904223U; const size_t b=random%matches.size();
        if(a==b || std::hypot(matches[a].x-matches[b].x,matches[a].y-matches[b].y)<8) continue;
        const auto candidate=fit_similarity(matches,{a,b});
        const double scale=std::hypot(candidate.a,candidate.b);
        if(scale<.5 || scale>2) continue;
        std::vector<size_t> inliers;
        for(size_t j=0;j<matches.size();++j) {
            const auto &p=matches[j]; const auto q=candidate.apply(p.x,p.y);
            if(std::hypot(q.first-p.u,q.second-p.v)<=1.5) inliers.push_back(j);
        }
        if(inliers.size()>best.size()) {
            best=std::move(inliers);
            const double ratio=double(best.size())/matches.size();
            if(ratio>.999) iterations=std::min(iterations,std::max(50,i+1));
            else if(ratio>0) iterations=std::min(iterations,std::max({50,i+1,int(std::ceil(std::log(.005)/std::log(1-ratio*ratio)))}));
        }
    }
    if(best.size()<10) return {};
    return fit_similarity(matches,best);
}

} // namespace st_desktop
#endif
