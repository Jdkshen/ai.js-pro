#include "sift_matcher.h"
#include <jni.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <codecvt>
#include <cstring>
#include <filesystem>
#include <iomanip>
#include <locale>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#ifdef AIJS_HAS_SIFT
#include <opencv2/features.hpp>
#include <opencv2/geometry.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

namespace {
using Clock = std::chrono::steady_clock;
double since(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}
std::string utf8(JNIEnv *env, jstring text) {
    if (!text) return {};
    const jchar *chars = env->GetStringChars(text, nullptr);
    if (!chars) throw std::runtime_error("Cannot read SIFT path");
    std::u16string value(reinterpret_cast<const char16_t *>(chars), env->GetStringLength(text));
    env->ReleaseStringChars(text, chars);
    return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.to_bytes(value);
}
struct Options {
    double scale, templateScale, ratio, ransac, minAngle, maxAngle;
    int limit, templateLimit, minimum, padding;
    bool track, gray, reuseScene, fast;
    explicit Options(JNIEnv *env, jdoubleArray values) {
        if (!values || env->GetArrayLength(values) != 14) throw std::invalid_argument("Invalid SIFT configuration");
        double a[14]; env->GetDoubleArrayRegion(values, 0, 14, a);
        for (double value : a) if (!std::isfinite(value)) throw std::invalid_argument("Nonfinite SIFT configuration");
        scale=a[0]; templateScale=a[1]; limit=static_cast<int>(a[2]); templateLimit=static_cast<int>(a[3]);
        ratio=a[4]; minimum=static_cast<int>(a[5]); ransac=a[6]; minAngle=a[7]; maxAngle=a[8];
        padding=static_cast<int>(a[9]); track=a[10]!=0; gray=a[11]!=0;
        reuseScene=a[12]!=0; fast=a[13]!=0;
        if ((scale!=0 && (scale<0.1 || scale>4)) || templateScale<0.1 || templateScale>4 ||
            limit<0 || limit>10000 || templateLimit<0 || templateLimit>10000 || ratio<0.01 || ratio>0.99 ||
            minimum<4 || minimum>10000 || padding<0 || padding>2048 || ransac<0.1 || ransac>100 ||
            minAngle<0 || minAngle>90 || maxAngle<90 || maxAngle>180)
            throw std::invalid_argument("Invalid SIFT configuration range");
    }
};
struct Input {
    cv::Mat owned;
    std::shared_ptr<const cv::Mat> frame;
    const cv::Mat *mat=nullptr;
    bool bgr=false;
    const char *mode="mat";
    Input(JNIEnv *env, int kind, int64_t value, int64_t engine, jstring path) {
        if (kind==0) { owned=cv::imread(utf8(env,path),cv::IMREAD_UNCHANGED); mat=&owned; bgr=true; mode="path"; }
        else if (kind==1 && value) mat=reinterpret_cast<const cv::Mat *>(value);
        else if (kind==2) { frame=aijsSiftBorrowFrame(engine,value); mat=frame.get(); mode="frame"; }
        if (!mat || mat->empty()) throw std::invalid_argument("SIFT input is empty, missing or recycled");
        if (mat->depth()!=CV_8U) throw std::invalid_argument("SIFT input must use 8-bit pixels");
    }
};
cv::Rect checkedRegion(int x,int y,int w,int h,const cv::Mat &image) {
    if (x<0 || y<0 || w<=0 || h<=0 || static_cast<int64_t>(x)+w>image.cols || static_cast<int64_t>(y)+h>image.rows)
        throw std::invalid_argument("region is empty or outside the image");
    return {x,y,w,h};
}
cv::Rect readRegion(JNIEnv *env,jintArray array,const cv::Mat &image,bool &explicitRegion) {
    explicitRegion=array && env->GetArrayLength(array)!=0;
    if (!explicitRegion) return {0,0,image.cols,image.rows};
    if (env->GetArrayLength(array)!=4) throw std::invalid_argument("region must be [x,y,width,height]");
    jint a[4]; env->GetIntArrayRegion(array,0,4,a);
    return checkedRegion(a[0],a[1],a[2],a[3],image);
}
struct Features {
    cv::Mat gray, nextGray, color, resized, descriptors;
    std::vector<cv::KeyPoint> keys;
    double sx=1, sy=1;
    int x=0, y=0;
    bool ready=false, reused=false;
    int featureLimit=-1;
    double checkMs=0;
    static bool identical(const cv::Mat &a,const cv::Mat &b) {
        if (a.size()!=b.size() || a.type()!=b.type() || a.empty()) return false;
        const size_t bytes=static_cast<size_t>(a.cols)*a.elemSize();
        if (a.isContinuous() && b.isContinuous()) return std::memcmp(a.data,b.data,bytes*a.rows)==0;
        for (int row=0;row<a.rows;row++) if (std::memcmp(a.ptr(row),b.ptr(row),bytes)!=0) return false;
        return true;
    }
    void extract(const Input &input,const cv::Rect &roi,double scale,bool preview,const cv::Ptr<cv::SIFT> &sift,
                 bool reuse=false) {
        cv::Mat source=(*input.mat)(roi);
        const int w=std::max(1,static_cast<int>(std::lround(roi.width*scale)));
        const int h=std::max(1,static_cast<int>(std::lround(roi.height*scale)));
        if (w!=roi.width || h!=roi.height) {
            cv::resize(source,resized,{w,h},0,0,cv::INTER_AREA); source=resized;
        }
        if (source.channels()==1) source.copyTo(nextGray);
        else if (source.channels()==3) cv::cvtColor(source,nextGray,cv::COLOR_BGR2GRAY);
        else if (source.channels()==4) cv::cvtColor(source,nextGray,input.bgr ? cv::COLOR_BGRA2GRAY : cv::COLOR_RGBA2GRAY);
        else throw std::invalid_argument("Unsupported SIFT input channels");
        if (preview && source.channels()!=1) {
            if (source.channels()==4) cv::cvtColor(source,color,input.bgr ? cv::COLOR_BGRA2BGR : cv::COLOR_RGBA2BGR);
            else source.copyTo(color);
        } else color.release();
        const auto checkStart=Clock::now();
        // Cache the actual SIFT input, not a frame ID or a sampled/hash-only signature.
        // Caller-owned images may be modified in place; exact equality prevents stale features.
        reused=reuse && ready && featureLimit==sift->getNFeatures() && identical(gray,nextGray);
        checkMs=since(checkStart);
        if (!reused) {
            ready=false;
            std::swap(gray,nextGray);
            keys.clear(); sift->detectAndCompute(gray,cv::noArray(),keys,descriptors);
            featureLimit=sift->getNFeatures(); ready=true;
        }
        sx=w/static_cast<double>(roi.width); sy=h/static_cast<double>(roi.height); x=roi.x; y=roi.y;
    }
};
struct Timings { double input=0, features=0, cacheCheck=0, flann=0, geometry=0, draw=0; int passes=0; };
struct Result {
    bool found=false;
    std::array<cv::Point2d,4> corners{};
    std::array<double,4> angles{};
    cv::Point2d center;
    int inliers=0, candidates=0, sceneFeatures=0;
    bool featureCacheHit=false, indexCacheHit=false;
    double searchScale=1;
};
bool validQuad(Result &r,double minAngle,double maxAngle) {
    double direction=0, area=0;
    for (int i=0;i<4;i++) {
        const auto a=r.corners[i], b=r.corners[(i+1)%4], d=r.corners[(i+3)%4];
        if (!std::isfinite(a.x) || !std::isfinite(a.y)) return false;
        const auto u=b-a, v=d-a;
        const double length=std::sqrt(u.dot(u)*v.dot(v)), cross=u.x*v.y-u.y*v.x;
        if (length<1 || std::abs(cross)<0.001 || (direction!=0 && cross*direction<=0)) return false;
        direction=cross;
        const double angle=std::acos(std::clamp(u.dot(v)/length,-1.0,1.0))*180/CV_PI;
        if (angle<minAngle || angle>maxAngle) return false;
        r.angles[i]=angle; area+=a.x*b.y-a.y*b.x;
    }
    return std::abs(area)>=32;
}
class Session {
public:
    std::mutex mutex;
    Features target, scene;
    cv::Ptr<cv::SIFT> detector;
    cv::Ptr<cv::DescriptorMatcher> matcher;
    double prepareMs=0;
    int limit=0, width=0, height=0;
    bool hasPrevious=false;
    bool indexReady=false;
    std::array<double,4> bounds{};
    Session(const Input &input,const Options &o,Clock::time_point start) {
        const auto templateDetector=cv::SIFT::create(o.templateLimit);
        target.extract(input,{0,0,input.mat->cols,input.mat->rows},o.templateScale,!o.gray,templateDetector);
        detector=cv::SIFT::create(o.limit); limit=o.limit;
        matcher=cv::DescriptorMatcher::create(cv::DescriptorMatcher::FLANNBASED);
        prepareMs=since(start);
    }
    cv::Rect trackingRegion(const cv::Mat &input,int padding) const {
        // Clamp in double before integer conversion, including badly extrapolated corners.
        // Outward alignment keeps subpixel homography jitter from changing the SIFT input
        // every frame. Small explicit paddings retain their original pixel boundaries.
        const double grid=padding>=16 ? 16 : 1;
        const int x=static_cast<int>(std::clamp(std::floor((bounds[0]-padding)/grid)*grid,0.0,static_cast<double>(input.cols)));
        const int y=static_cast<int>(std::clamp(std::floor((bounds[1]-padding)/grid)*grid,0.0,static_cast<double>(input.rows)));
        const int right=static_cast<int>(std::clamp(std::ceil((bounds[2]+padding)/grid)*grid,0.0,static_cast<double>(input.cols)));
        const int bottom=static_cast<int>(std::clamp(std::ceil((bounds[3]+padding)/grid)*grid,0.0,static_cast<double>(input.rows)));
        return {x,y,std::max(0,right-x),std::max(0,bottom-y)};
    }
    Result search(const Input &input,const cv::Rect &region,const Options &o,const std::string &draw,int support,Timings &t,
                  double scaleOverride=0) {
        const int minimum=std::max(o.minimum,support);
        if (target.descriptors.rows<minimum) return {};
        const double scale=scaleOverride>0 ? scaleOverride : o.scale==0 ?
            (static_cast<int64_t>(region.width)*region.height>1000000 ? 0.5 : 1) : o.scale;
        auto start=Clock::now();
        t.passes++;
        scene.extract(input,region,scale,!draw.empty() && !o.gray,detector,o.reuseScene);
        t.features+=since(start)-scene.checkMs; t.cacheCheck+=scene.checkMs;
        if (!scene.reused) { matcher->clear(); indexReady=false; }
        if (scene.descriptors.rows<2) return {};
        start=Clock::now();
        std::vector<std::vector<cv::DMatch>> neighbors;
        const bool reusedIndex=indexReady;
        if (!indexReady) {
            // A prior failed train() may have left an untrained descriptor collection.
            matcher->clear();
            matcher->add(std::vector<cv::Mat>{scene.descriptors});
            matcher->train(); indexReady=true;
        }
        // The two-matrix overload clones and rebuilds a FLANN index on every call.
        // Keep the trained scene index while its descriptors are unchanged.
        matcher->knnMatch(target.descriptors,neighbors,2);
        std::vector<cv::DMatch> good;
        std::vector<cv::Point2f> from,to;
        good.reserve(neighbors.size()); from.reserve(neighbors.size()); to.reserve(neighbors.size());
        for (const auto &pair:neighbors) {
            if (pair.size()>=2 && pair[0].distance<o.ratio*pair[1].distance) {
                const auto m=pair[0]; good.push_back(m);
                from.push_back(target.keys[m.queryIdx].pt); to.push_back(scene.keys[m.trainIdx].pt);
            }
        }
        t.flann+=since(start);
        if (static_cast<int>(good.size())<minimum) return {};
        start=Clock::now();
        cv::Mat mask;
        const cv::Mat homography=cv::findHomography(from,to,cv::RANSAC,o.ransac,mask);
        if (homography.empty() || mask.total()!=good.size()) { t.geometry+=since(start); return {}; }
        std::vector<cv::DMatch> inliers;
        for (size_t i=0;i<good.size();i++) if (mask.ptr<uint8_t>()[i]!=0) inliers.push_back(good[i]);
        if (static_cast<int>(inliers.size())<minimum) { t.geometry+=since(start); return {}; }
        // Preserve the float-point convention of the previous MatOfPoint2f Java API.
        std::vector<cv::Point2f> source={{0,0},{static_cast<float>(target.gray.cols),0},
            {static_cast<float>(target.gray.cols),static_cast<float>(target.gray.rows)},
            {0,static_cast<float>(target.gray.rows)}, {target.gray.cols/2.0f,target.gray.rows/2.0f}}, transformed;
        cv::perspectiveTransform(source,transformed,homography);
        Result r;
        for (int i=0;i<4;i++) r.corners[i]={transformed[i].x/scene.sx+scene.x,transformed[i].y/scene.sy+scene.y};
        r.center={transformed[4].x/scene.sx+scene.x,transformed[4].y/scene.sy+scene.y};
        r.found=validQuad(r,o.minAngle,o.maxAngle) && std::isfinite(r.center.x) && std::isfinite(r.center.y);
        r.inliers=static_cast<int>(inliers.size()); r.candidates=static_cast<int>(good.size()); r.sceneFeatures=static_cast<int>(scene.keys.size());
        r.featureCacheHit=scene.reused; r.indexCacheHit=reusedIndex; r.searchScale=scene.sx;
        t.geometry+=since(start);
        if (!r.found || draw.empty()) return r;
        start=Clock::now();
        const cv::Mat &left=o.gray || target.color.empty() ? target.gray : target.color;
        const cv::Mat &right=o.gray || scene.color.empty() ? scene.gray : scene.color;
        cv::Mat output;
        cv::drawMatches(left,target.keys,right,scene.keys,inliers,output,{0,200,255},{0,0,0},
                        std::vector<char>{},cv::DrawMatchesFlags::NOT_DRAW_SINGLE_POINTS);
        for (int i=0;i<4;i++) cv::line(output,transformed[i]+cv::Point2f(static_cast<float>(left.cols),0),
                                      transformed[(i+1)%4]+cv::Point2f(static_cast<float>(left.cols),0),{0,255,0},2);
        const auto parent=std::filesystem::path(draw).parent_path();
        if (!parent.empty()) { std::error_code error; std::filesystem::create_directories(parent,error);
            if (error && !std::filesystem::is_directory(parent)) throw std::invalid_argument("Cannot create SIFT output directory"); }
        if (!cv::imwrite(draw,output)) throw std::invalid_argument("Cannot write SIFT output");
        t.draw+=since(start); return r;
    }
    std::string match(const Input &input,const Options &o,const cv::Rect &region,bool explicitRegion,
                      const std::string &draw,Timings &t,Clock::time_point started) {
        if (width!=input.mat->cols || height!=input.mat->rows) hasPrevious=false;
        if (limit!=o.limit) { detector->setNFeatures(o.limit); limit=o.limit; }
        const char *mode=explicitRegion ? "region" : "full";
        Result r;
        if (o.track && !explicitRegion && hasPrevious) {
            const auto local=trackingRegion(*input.mat,o.padding);
            if (!local.empty()) r=search(input,local,o,draw,8,t);
            if (r.found) mode="tracked";
        }
        if (!r.found && o.fast) {
            const double scale=o.scale==0 ? (static_cast<int64_t>(region.width)*region.height>1000000 ? 0.5 : 1) : o.scale;
            // Like a bounded first search: accept strong geometry at a smaller scale,
            // and retain the requested resolution as the fallback for difficult targets.
            if (scale>=0.3 && static_cast<double>(region.width)*region.height*scale*scale>250000)
                r=search(input,region,o,draw,8,t,scale*(2.0/3.0));
        }
        if (!r.found) r=search(input,region,o,draw,4,t);
        if (!r.found) {
            hasPrevious=false;
            // FLANN is approximate; a weak randomized index must not make a miss sticky.
            matcher->clear(); indexReady=false;
            return "null";
        }
        bounds={r.corners[0].x,r.corners[0].y,r.corners[0].x,r.corners[0].y};
        for (auto p:r.corners) { bounds[0]=std::min(bounds[0],p.x); bounds[1]=std::min(bounds[1],p.y);
            bounds[2]=std::max(bounds[2],p.x); bounds[3]=std::max(bounds[3],p.y); }
        width=input.mat->cols; height=input.mat->rows; hasPrevious=true;
        std::ostringstream json; json.imbue(std::locale::classic()); json<<std::setprecision(12);
        auto point=[&json](cv::Point2d p) { json<<"{\"x\":"<<p.x<<",\"y\":"<<p.y<<"}"; };
        json<<"{";
        const char *names[]={"topLeft","topRight","bottomRight","bottomLeft"};
        for (int i=0;i<4;i++) { json<<'"'<<names[i]<<"\":"; point(r.corners[i]); json<<','; }
        json<<"\"corners\":[";
        for (int i=0;i<4;i++) { if(i) json<<','; point(r.corners[i]); }
        json<<"],\"angles\":[";
        for (int i=0;i<4;i++) { if(i) json<<','; json<<r.angles[i]; }
        json<<"],\"center\":"; point(r.center);
        json<<",\"centerX\":"<<r.center.x<<",\"centerY\":"<<r.center.y
            <<",\"matchCount\":"<<r.inliers<<",\"candidateCount\":"<<r.candidates
            <<",\"sceneFeatureCount\":"<<r.sceneFeatures<<",\"templateFeatureCount\":"<<target.keys.size()
            <<",\"method\":\"SIFT\",\"backend\":\"cpp\",\"searchMode\":\""<<mode
            <<"\",\"inputMode\":\""<<input.mode<<"\",\"templatePrepareMs\":"<<prepareMs
            <<",\"featureCacheHit\":"<<(r.featureCacheHit ? "true" : "false")
            <<",\"indexCacheHit\":"<<(r.indexCacheHit ? "true" : "false")
            <<",\"searchScale\":"<<r.searchScale<<",\"searchPasses\":"<<t.passes
            <<",\"timings\":{\"inputMs\":"<<t.input<<",\"featureMs\":"<<t.features<<",\"flannMs\":"<<t.flann
            <<",\"cacheCheckMs\":"<<t.cacheCheck
            <<",\"geometryMs\":"<<t.geometry<<",\"drawMs\":"<<t.draw<<"},\"elapsedMs\":"<<since(started)<<'}';
        return json.str();
    }
};
std::mutex sessionsMutex;
std::unordered_map<int64_t,std::shared_ptr<Session>> sessions;
std::atomic<int64_t> nextSession{1};
std::shared_ptr<Session> findSession(int64_t id) {
    std::lock_guard<std::mutex> lock(sessionsMutex);
    const auto it=sessions.find(id);
    if (it==sessions.end()) throw std::invalid_argument("SIFT matcher has been recycled");
    return it->second;
}
void throwError(JNIEnv *env,const std::exception &error) {
    if (!env->ExceptionCheck()) { jclass type=env->FindClass("java/lang/IllegalArgumentException");
        if (type) { env->ThrowNew(type,error.what()); env->DeleteLocalRef(type); } }
}
} // namespace
#endif

#define SIFT_JNI(name) Java_com_stardust_autojs_core_image_SiftMatcher_##name
static void unsupported(JNIEnv *env) {
    jclass type=env->FindClass("java/lang/UnsupportedOperationException");
    if(type) { env->ThrowNew(type,"Native SIFT requires an arm64-v8a OpenCV features build"); env->DeleteLocalRef(type); }
}
extern "C" JNIEXPORT jlong JNICALL SIFT_JNI(nativeCreate)(JNIEnv *env,jclass,jobject,jint kind,jlong value,jlong engine,jstring path,jdoubleArray options) {
#ifdef AIJS_HAS_SIFT
    try {
        const auto start=Clock::now(); Options o(env,options); Input input(env,kind,value,engine,path);
        auto session=std::make_shared<Session>(input,o,start);
        const auto id=nextSession.fetch_add(1);
        std::lock_guard<std::mutex> lock(sessionsMutex); sessions.emplace(id,std::move(session)); return id;
    } catch(const std::exception &error) { throwError(env,error); }
#else
    unsupported(env);
#endif
    return 0;
}
extern "C" JNIEXPORT jstring JNICALL SIFT_JNI(nativeMatch)(JNIEnv *env,jclass,jlong id,jobject,jint kind,jlong value,jlong engine,jstring path,jdoubleArray options,jintArray area,jstring drawing) {
#ifdef AIJS_HAS_SIFT
    try {
        const auto start=Clock::now(); auto session=findSession(id); std::lock_guard<std::mutex> lock(session->mutex);
        Options o(env,options); Input input(env,kind,value,engine,path); Timings t; t.input=since(start);
        bool explicitRegion=false; const auto region=readRegion(env,area,*input.mat,explicitRegion);
        const auto json=session->match(input,o,region,explicitRegion,utf8(env,drawing),t,start);
        return env->NewStringUTF(json.c_str());
    } catch(const std::exception &error) { throwError(env,error); }
#else
    unsupported(env);
#endif
    return nullptr;
}
extern "C" JNIEXPORT jint JNICALL SIFT_JNI(nativeFeatureCount)(JNIEnv *env,jclass,jlong id) {
#ifdef AIJS_HAS_SIFT
    try { return static_cast<jint>(findSession(id)->target.keys.size()); } catch(const std::exception &error) { throwError(env,error); }
#else
    unsupported(env);
#endif
    return 0;
}
extern "C" JNIEXPORT jdouble JNICALL SIFT_JNI(nativePrepareMs)(JNIEnv *env,jclass,jlong id) {
#ifdef AIJS_HAS_SIFT
    try { return findSession(id)->prepareMs; } catch(const std::exception &error) { throwError(env,error); }
#else
    unsupported(env);
#endif
    return 0;
}
extern "C" JNIEXPORT void JNICALL SIFT_JNI(nativeClose)(JNIEnv *,jclass,jlong id) {
#ifdef AIJS_HAS_SIFT
    // Destruct outside the registry lock. Any in-flight call holds its own shared_ptr.
    std::shared_ptr<Session> removed;
    { std::lock_guard<std::mutex> lock(sessionsMutex); auto it=sessions.find(id);
      if(it!=sessions.end()) { removed=std::move(it->second); sessions.erase(it); } }
#endif
}
