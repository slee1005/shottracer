#ifndef SHOTTRACER_DESKTOP_TRACKING_HPP
#define SHOTTRACER_DESKTOP_TRACKING_HPP

#include "desktop_camera.hpp"
#include <numeric>

namespace st_desktop {

struct Detection { int frame; double x,y,strength; };
struct Record { double time; Transform camera; bool gone=false,ready=false; std::vector<Detection> candidates; };
struct Frame { Image raw,smooth; std::vector<Image> levels; Transform camera; double time=0; };

inline std::vector<uint8_t> dilate(const std::vector<uint8_t> &mask,int w,int h,int radius) {
    std::vector<uint8_t> out(mask.size());
    for(int y=0;y<h;++y) for(int x=0;x<w;++x) if(mask[size_t(y)*w+x]) {
        for(int dy=-radius;dy<=radius;++dy) {
            const int yy=y+dy;
            if(yy<0 || yy>=h) continue;
            const int reach=int(std::round(std::sqrt(double(radius*radius-dy*dy))));
            for(int xx=std::max(0,x-reach);xx<=std::min(w-1,x+reach);++xx) out[size_t(yy)*w+xx]=1;
        }
    }
    return out;
}

template<class Function>
inline void components(std::vector<uint8_t> mask,int w,int h,Function function) {
    std::vector<int> todo;
    for(int i=0;i<w*h;++i) if(mask[size_t(i)]) {
        todo.clear(); todo.push_back(i); mask[size_t(i)]=0;
        for(size_t j=0;j<todo.size();++j) {
            const int x=todo[j]%w,y=todo[j]/w;
            for(int dy=-1;dy<=1;++dy) for(int dx=-1;dx<=1;++dx) {
                const int xx=x+dx,yy=y+dy;
                if(xx<0 || xx>=w || yy<0 || yy>=h) continue;
                const size_t at=size_t(yy)*w+xx;
                if(mask[at]) { mask[at]=0; todo.push_back(int(at)); }
            }
        }
        function(todo);
    }
}

class Tracker {
    int w_,h_,mode_;
    double seed_x_,seed_y_,radius_=3,horizon_,flight_hint_;
    bool color_horizon_=false,launched_=false,ended_=false,finished_=false;
    int processed_=0,misses_=0;
    Frame older_,previous_;
    std::vector<Record> records_;
    std::vector<STPoint> points_;
    std::vector<double> address_;
    int half_=7;
    STModel model_{};
    double putt_x_=0,putt_y_=0,vx_=0,vy_=0,still_since_=-1;
    std::vector<double> putt_template_;

    void refine(const Image &image) {
        const int cx=int(std::round(seed_x_)),cy=int(std::round(seed_y_));
        const int x0=std::max(0,cx-6),y0=std::max(0,cy-6),x1=std::min(w_,cx+7),y1=std::min(h_,cy+7);
        Image roi(x1-x0,y1-y0);
        for(int y=0;y<roi.h;++y) for(int x=0;x<roi.w;++x) roi.pixels[size_t(y)*roi.w+x]=image.at(x+x0,y+y0);
        roi=blur(roi);
        std::vector<double> contrast(roi.pixels.size());
        double maximum=-1; int peak=0;
        for(int y=0;y<roi.h;++y) for(int x=0;x<roi.w;++x) {
            double background=0;
            for(int yy=-4;yy<=4;++yy) for(int xx=-4;xx<=4;++xx) background+=roi.at(x+xx,y+yy);
            const int index=y*roi.w+x; contrast[size_t(index)]=roi.at(x,y)-background/81;
            if(contrast[size_t(index)]>maximum) { maximum=contrast[size_t(index)]; peak=index; }
        }
        if(mode_==1 && contrast[size_t(cy-y0)*roi.w+cx-x0]<-6) {
            maximum=-1;
            for(size_t i=0;i<contrast.size();++i) { contrast[i]=-contrast[i]; if(contrast[i]>maximum) { maximum=contrast[i]; peak=int(i); } }
        }
        if(maximum>=6) {
            std::vector<uint8_t> mask(contrast.size());
            for(size_t i=0;i<mask.size();++i) mask[i]=contrast[i]>.45*maximum;
            components(mask,roi.w,roi.h,[&](const std::vector<int> &part) {
                if(std::find(part.begin(),part.end(),peak)==part.end()) return;
                double x=0,y=0;
                for(int i:part) { x+=i%roi.w; y+=i/roi.w; }
                seed_x_=x0+x/part.size(); seed_y_=y0+y/part.size();
                radius_=std::clamp(std::sqrt(part.size()/3.141592653589793),1.5,12.0);
            });
        }
        half_=std::max(4,int(std::round(radius_*2.5)));
        for(int y=-half_;y<=half_;++y) for(int x=-half_;x<=half_;++x)
            address_.push_back(image.sample(seed_x_+x,seed_y_+y));
    }

    double gray_horizon(const Image &image) const {
        std::vector<double> rows(static_cast<size_t>(h_));
        for(int y=0;y<h_;++y) for(int x=0;x<w_;++x) rows[size_t(y)]+=image.at(x,y)/w_;
        std::vector<double> smooth(rows.size());
        for(int y=0;y<h_;++y) {
            double weight=0;
            for(int d=-9;d<=9;++d) { const double a=std::exp(-d*d/18.0); weight+=a; smooth[size_t(y)]+=a*rows[size_t(std::clamp(y+d,0,h_-1))]; }
            smooth[size_t(y)]/=weight;
        }
        const int lo=int(.05*h_),hi=int(std::min(seed_y_-.05*h_,.85*h_)),step=std::max(3,h_/100);
        if(hi-lo<10) return .4*h_;
        double score=8,best=.4*h_;
        for(int y=lo;y<hi;++y) {
            const double d=smooth[size_t(std::max(0,y-step))]-smooth[size_t(std::min(h_-1,y+step))];
            if(d>score) { score=d; best=y; }
        }
        return best;
    }

    bool gone(const Frame &frame) const {
        const auto center=frame.camera.apply(seed_x_,seed_y_);
        if(center.first-half_<0 || center.second-half_<0 || center.first+half_>=w_ || center.second+half_>=h_) return true;
        const double mean=std::accumulate(address_.begin(),address_.end(),0.0)/address_.size();
        std::vector<double> patch; patch.reserve(address_.size());
        for(int y=-half_;y<=half_;++y) for(int x=-half_;x<=half_;++x) {
            const auto p=frame.camera.apply(seed_x_+x,seed_y_+y);
            patch.push_back(frame.raw.sample(p.first,p.second));
        }
        const double current=std::accumulate(patch.begin(),patch.end(),0.0)/patch.size();
        double cross=0,a=0,b=0;
        for(size_t i=0;i<patch.size();++i) { const double x=address_[i]-mean,y=patch[i]-current; cross+=x*y; a+=x*x; b+=y*y; }
        return cross/std::sqrt(std::max(1e-12,a*b))<.5;
    }

    std::vector<Detection> candidates(const Frame &before,const Frame &middle,const Frame &after,int frame) const {
        std::vector<uint8_t> difference(size_t(w_)*h_),mask(difference.size());
        std::array<int,256> histogram{};
        const auto reference=middle.camera.inverse();
        for(int y=0;y<h_;++y) for(int x=0;x<w_;++x) {
            const auto ref=reference.apply(x,y),p=before.camera.apply(ref.first,ref.second),q=after.camera.apply(ref.first,ref.second);
            const double value=middle.smooth.at(x,y);
            const auto d=uint8_t(std::clamp(std::min(std::abs(value-before.smooth.sample(p.first,p.second)),std::abs(value-after.smooth.sample(q.first,q.second))),0.0,255.0));
            difference[size_t(y)*w_+x]=d; ++histogram[d];
        }
        int median=0,total=0;
        for(;median<255;++median) { total+=histogram[size_t(median)]; if(total>=w_*h_/2) break; }
        const double threshold=std::max(8.0,5.0*(median+1));
        for(size_t i=0;i<mask.size();++i) mask[i]=difference[i]>threshold;
        auto merged=dilate(mask,w_,h_,4);
        std::vector<uint8_t> big(mask.size());
        const double big_area=std::max(std::pow(.03*w_,2),2*3.141592653589793*std::pow(radius_+4,2));
        components(std::move(merged),w_,h_,[&](const std::vector<int> &part) {
            if(part.size()>big_area) for(int i:part) big[size_t(i)]=1;
        });
        big=dilate(big,w_,h_,7);
        // Preserve the clicked ball's measured footprint on small phone rasters.
        // The desktop width-based cap alone can reject an entire nearby ball.
        const double max_area=std::max(std::pow(.012*w_,2),3*3.141592653589793*radius_*radius_);
        std::vector<Detection> result;
        components(std::move(mask),w_,h_,[&](const std::vector<int> &part) {
            if(part.size()>max_area) return;
            double x=0,y=0,strength=0; int minx=w_,miny=h_,maxx=0,maxy=0;
            for(int i:part) { x+=i%w_; y+=i/w_; strength=std::max(strength,double(difference[size_t(i)])); minx=std::min(minx,i%w_); maxx=std::max(maxx,i%w_); miny=std::min(miny,i/w_); maxy=std::max(maxy,i/w_); }
            x/=part.size(); y/=part.size();
            if(big[size_t(std::lround(y))*w_+size_t(std::lround(x))]) return;
            const int width=maxx-minx+1,height=maxy-miny+1;
            if(std::max(width,height)>6*std::max(1,std::min(width,height)) && part.size()>12) return;
            const auto ref=reference.apply(x,y);
            result.push_back({frame,ref.first,ref.second,strength});
        });
        return result;
    }

    bool fit(const std::vector<STPoint> &points,double lo,double hi,STModel &result) const {
        std::vector<double> samples;
        for(const auto &p:points) samples.insert(samples.end(),{p.time,p.x,p.y});
        const double minT=flight_hint_>0?flight_hint_:3.5,maxT=flight_hint_>0?flight_hint_:7.5,typical=flight_hint_>0?flight_hint_:5.5;
        return st_fit_model(samples.data(),int(points.size()),seed_x_,seed_y_,horizon_,lo,hi,minT,maxT,typical,&result)==0 && result.b>0 && result.g>0;
    }

    bool seed(int index,int max_gap,double ratio,std::vector<Detection> &found) const {
        const int last=int(records_.size())-1;
        for(int offset:{0,2,4}) if(!records_[size_t(std::min(last,index+offset))].gone) return false;
        const double dt=records_[size_t(index)].time-records_[size_t(index-1)].time;
        double best=-1;
        auto step=[](double ax,double ay,double bx,double by,double low,double high) {
            const double a=std::hypot(ax,ay),b=std::hypot(bx,by);
            return a>0 && b>1 && b>=low*a && b<=high*a && (ax*bx+ay*by)/(a*b)>std::cos(3.141592653589793/6);
        };
        for(const auto &a:records_[size_t(index)].candidates) {
            const double dx=a.x-seed_x_,dy=a.y-seed_y_,distance=std::hypot(dx,dy);
            if(a.strength<25 || distance<2 || distance>.3*w_ || dy>-.3*distance) continue;
            for(int gap=1;gap<=max_gap && index+gap<last;++gap) for(const auto &b:records_[size_t(index+gap)].candidates) {
                const double span=records_[size_t(b.frame)].time-records_[size_t(a.frame)].time;
                const double bx=(b.x-a.x)/span,by=(b.y-a.y)/span;
                if(b.strength<15 || std::hypot(bx,by)<(max_gap==1?.02:.01)*w_/dt || !step(dx/dt,dy/dt,bx,by,ratio,2.5)) continue;
                for(int next=1;next<=max_gap && b.frame+next<last;++next) for(const auto &c:records_[size_t(b.frame+next)].candidates) {
                    const double span2=records_[size_t(c.frame)].time-records_[size_t(b.frame)].time;
                    if(c.strength<15 || !step(bx,by,(c.x-b.x)/span2,(c.y-b.y)/span2,.3,1.1)) continue;
                    const double score=a.strength+b.strength+c.strength-10*(gap+next-2);
                    if(score>best) { best=score; found={a,b,c}; }
                }
            }
        }
        return best>=0;
    }

    void flight(bool final) {
        if(ended_) return;
        if(!launched_) {
            const int last=int(records_.size())-(final?3:8);
            for(int i=1;i<=last;++i) {
                std::vector<Detection> found;
                if(!seed(i,1,.35,found) && !seed(i,3,.15,found)) continue;
                std::vector<STPoint> samples;
                for(const auto &c:found) samples.push_back({records_[size_t(c.frame)].time,c.x,c.y,c.strength/255});
                const double first=records_[size_t(i)].time,dt=first-records_[size_t(i-1)].time;
                if(!fit(samples,first-dt,first-.05*dt,model_)) continue;
                launch_lo_=first-dt; launch_hi_=first-.05*dt;
                points_.push_back({model_.t0,seed_x_,seed_y_,1});
                points_.insert(points_.end(),samples.begin(),samples.end());
                processed_=found.back().frame; launched_=true; break;
            }
        }
        while(launched_ && processed_+1<int(records_.size()) && records_[size_t(processed_+1)].ready && misses_<8) {
            const int index=++processed_; const auto &record=records_[size_t(index)];
            double x=0,y=0,px=0,py=0;
            if(st_model_at(&model_,record.time-model_.t0,&x,&y) || st_model_at(&model_,records_[size_t(index-1)].time-model_.t0,&px,&py)) break;
            const double gate=std::max(5.0,.6*std::hypot(x-px,y-py))+1.5*misses_;
            const Detection *chosen=nullptr; double best=std::numeric_limits<double>::infinity();
            for(const auto &c:record.candidates) {
                const double distance=std::hypot(c.x-x,c.y-y),score=distance-.05*c.strength;
                if(distance<=gate && score<best) { best=score; chosen=&c; }
            }
            if(chosen) {
                points_.push_back({record.time,chosen->x,chosen->y,chosen->strength/255});
                std::vector<STPoint> observed(points_.begin()+1,points_.end()); STModel updated{};
                if(fit(observed,launch_lo_,launch_hi_,updated)) { model_=updated; points_[0].time=model_.t0; misses_=0; }
                else { points_.pop_back(); ++misses_; }
            } else ++misses_;
        }
        if(misses_>=8) ended_=true;
    }

    double launch_lo_=0,launch_hi_=0;
    void putt(const Frame &frame) {
        const auto &image=frame.raw;
        if(putt_template_.empty()) {
            half_=std::min({std::max(5,int(std::round(radius_*2.5))),int(seed_x_)-1,int(seed_y_)-1,w_-int(seed_x_)-2,h_-int(seed_y_)-2});
            putt_x_=seed_x_; putt_y_=seed_y_;
            for(int y=-half_;y<=half_;++y) for(int x=-half_;x<=half_;++x) putt_template_.push_back(image.sample(seed_x_+x,seed_y_+y));
            return;
        }
        if(ended_) return;
        const double dt=frame.time-previous_.time;
        const auto pred=frame.camera.apply(putt_x_+vx_*dt,putt_y_+vy_*dt);
        const int reach=int(std::clamp(std::max(radius_*3+6,1.5*std::hypot(vx_,vy_)*dt+6),6.0,64.0));
        const double mean=std::accumulate(putt_template_.begin(),putt_template_.end(),0.0)/putt_template_.size();
        double variance=0; for(double p:putt_template_) variance+=(p-mean)*(p-mean);
        double best=-1,distance=1e30; int bx=0,by=0;
        for(int y=std::max(half_,int(std::lround(pred.second))-reach);y<=std::min(h_-half_-1,int(std::lround(pred.second))+reach);++y)
            for(int x=std::max(half_,int(std::lround(pred.first))-reach);x<=std::min(w_-half_-1,int(std::lround(pred.first))+reach);++x) {
                double sum=0,sq=0,cross=0; size_t i=0;
                for(int yy=-half_;yy<=half_;++yy) for(int xx=-half_;xx<=half_;++xx) { const double value=image.at(x+xx,y+yy); sum+=value; sq+=value*value; cross+=(putt_template_[i++]-mean)*value; }
                const double score=cross/std::sqrt(std::max(1e-12,variance*(sq-sum*sum/putt_template_.size()))),d=std::hypot(x-pred.first,y-pred.second);
                if(score>best+1e-8 || (std::abs(score-best)<1e-8 && d<distance)) { best=score; distance=d; bx=x; by=y; }
            }
        if(best<.45) { if(launched_ && ++misses_>std::max(6,int(.25/dt))) ended_=true; return; }
        misses_=0;
        const auto ref=frame.camera.inverse().apply(bx,by);
        if(!launched_ && std::hypot(ref.first-seed_x_,ref.second-seed_y_)>std::max(2.0,radius_)) {
            launched_=true; points_.push_back({previous_.time,seed_x_,seed_y_,1});
        }
        if(launched_) {
            const double dx=ref.first-putt_x_,dy=ref.second-putt_y_;
            vx_=.6*vx_+.4*dx/dt; vy_=.6*vy_+.4*dy/dt;
            points_.push_back({frame.time,ref.first,ref.second,best});
            if(std::hypot(dx,dy)<.15) { if(still_since_<0) still_since_=frame.time; else if(frame.time-still_since_>.5) ended_=true; } else still_since_=-1;
            if(best>.7) { size_t i=0; for(int yy=-half_;yy<=half_;++yy) for(int xx=-half_;xx<=half_;++xx) { putt_template_[i]=.85*putt_template_[i]+.15*image.at(bx+xx,by+yy); ++i; } }
        }
        putt_x_=ref.first; putt_y_=ref.second;
    }

public:
    Tracker(int w,int h,double x,double y,int mode,double flight) : w_(w),h_(h),mode_(mode),seed_x_(x),seed_y_(y),horizon_(.4*h),flight_hint_(flight) {}
    void mode(int value) { mode_=value; }
    bool reference_rgba(const uint8_t *pixels,int stride) {
        if(!records_.empty() || !pixels || stride<w_*4) return false;
        std::vector<double> rows(static_cast<size_t>(h_));
        for(int y=0;y<h_;++y) for(int x=0;x<w_;++x) {
            const auto *p=pixels+size_t(y)*stride+x*4;
            const double r=p[0],g=p[1],b=p[2],maximum=std::max({r,g,b}),minimum=std::min({r,g,b}),delta=maximum-minimum;
            double hue=0;
            if(delta>0) { if(maximum==r) hue=60*std::fmod((g-b)/delta+6,6); else if(maximum==g) hue=60*((b-r)/delta+2); else hue=60*((r-g)/delta+4); }
            if(hue>=60 && hue<=180 && maximum>=40 && delta*255>=50*maximum) rows[size_t(y)]+=1.0/w_;
        }
        auto smooth=rows;
        for(int y=0;y<h_;++y) { double weight=0,sum=0; for(int d=-9;d<=9;++d) { const double a=std::exp(-d*d/18.0); weight+=a; sum+=a*rows[size_t(std::clamp(y+d,0,h_-1))]; } smooth[size_t(y)]=sum/weight; }
        int y=std::clamp(int(seed_y_),0,h_-1);
        if(smooth[size_t(y)]<.5) return true;
        while(y>0 && smooth[size_t(y-1)]>=.5) --y;
        if(y>=.15*h_ && y<=seed_y_-.03*h_) { horizon_=y; color_horizon_=true; }
        return true;
    }
    int push(const uint8_t *pixels,int stride,double time) {
        if(finished_ || records_.size()>=2000 || (!records_.empty() && time<=records_.back().time)) return -1;
        Frame frame; frame.raw=Image(w_,h_); frame.time=time;
        for(int y=0;y<h_;++y) for(int x=0;x<w_;++x) frame.raw.pixels[size_t(y)*w_+x]=pixels[size_t(y)*stride+x];
        frame.smooth=blur(frame.raw); frame.levels=pyramid(frame.smooth);
        if(records_.empty()) { refine(frame.raw); if(!color_horizon_) horizon_=gray_horizon(frame.raw); }
        else frame.camera=camera_motion(previous_.levels,frame.levels).after(previous_.camera);
        records_.push_back({time,frame.camera,false,false,{}});
        records_.back().gone=gone(frame);
        if(mode_==1) putt(frame);
        else {
            if(records_.size()>=3) {
                auto &middle=records_[records_.size()-2];
                middle.candidates=candidates(older_,previous_,frame,int(records_.size())-2); middle.ready=true;
            }
            flight(false);
        }
        older_=std::move(previous_); previous_=std::move(frame);
        return ended_?2:launched_?1:0;
    }
    int finish() {
        if(!finished_) {
            if(mode_==0) flight(true);
            else if(points_.size()>5) {
                const auto original=points_;
                for(size_t i=2;i+2<points_.size();++i) {
                    double x=0,y=0;
                    for(size_t j=i-2;j<=i+2;++j) { x+=original[j].x; y+=original[j].y; }
                    points_[i].x=x/5; points_[i].y=y/5;
                }
            }
        }
        finished_=true; return ended_?2:launched_?1:0;
    }
    bool launched() const { return launched_; }
    const std::vector<STPoint> &points() const { return points_; }
    const std::vector<Record> &cameras() const { return records_; }
    const STModel &model() const { return model_; }
    Transform camera(double time) const {
        if(records_.empty()) return {};
        auto next=std::lower_bound(records_.begin(),records_.end(),time,[](const Record &a,double t){return a.time<t;});
        if(next==records_.begin()) return next->camera;
        if(next==records_.end()) return records_.back().camera;
        const auto &before=*(next-1); const double f=(time-before.time)/(next->time-before.time);
        const auto &a=before.camera,&b=next->camera;
        return {a.a+f*(b.a-a.a),a.b+f*(b.b-a.b),a.x+f*(b.x-a.x),a.y+f*(b.y-a.y)};
    }
};

} // namespace st_desktop
#endif
