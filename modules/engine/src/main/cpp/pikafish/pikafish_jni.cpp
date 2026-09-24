// Pikafish（中国象棋 NNUE 引擎，GPLv3）的 Android/JNI 包装。
//
// 背景：Pikafish 只提供命令行 UCI 界面（官方 main.cpp 就是 std::cin / std::cout 的循环），
// App 进程里没有终端。这里的做法是把 std::cin / std::cout / std::cerr 的底层 streambuf
// 换成自己实现的「命令队列」与「逐行收集器」，然后在独立线程里跑 UCIEngine::loop()：
//
//   Java/JS 侧                本文件                          引擎
//   nativeStart  ──►  建会话 + 重定向流 + 起线程  ──►  Attacks::init / UCIEngine::loop
//                     投喂 "uci" / setoption / isready，等 uciok / readyok
//   nativeAnalyze ──►  投喂 "position fen ..." + "go movetime N"，等 "bestmove"
//   nativeStop    ──►  投喂 "stop"（打断长搜索；go 会正常以 bestmove 收尾）
//
// analyze 返回 "最后一条带 pv 的 info 行\n bestmove 行"（原样两行，分数/PV 解析交给上层，
// 避免在 C++ 里重复实现 UCI 语法解析）。

#include <jni.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "attacks.h"
#include "misc.h"
#include "position.h"
#include "tune.h"
#include "uci.h"

namespace {

using namespace Stockfish;

std::string jstringToUtf8(JNIEnv* env, jstring text) {
    if (text == nullptr)
        return {};
    const char* chars = env->GetStringUTFChars(text, nullptr);
    std::string result = chars != nullptr ? chars : "";
    if (chars != nullptr)
        env->ReleaseStringUTFChars(text, chars);
    return result;
}

// ---------------------------------------------------------------- 输入队列
// UCI 循环用 std::getline(std::cin, ...) 读命令；underflow 在队列空时阻塞等待。
class LineFeeder: public std::streambuf {
   public:
    void pushLine(std::string line) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            queue_.push_back(std::move(line));
        }
        readable_.notify_all();
    }

    // 关闭后 underflow 返回 EOF，getline 失败，UCI 循环自然退出（兜底解阻塞）。
    void close() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            closed_ = true;
        }
        readable_.notify_all();
    }

   protected:
    int_type underflow() override {
        std::unique_lock<std::mutex> lock(mutex_);
        readable_.wait(lock, [this] { return closed_ || !queue_.empty(); });
        if (queue_.empty())
            return traits_type::eof();
        pending_ = queue_.front();
        queue_.pop_front();
        pending_.push_back('\n');
        char* base = pending_.data();
        setg(base, base, base + pending_.size());
        return traits_type::to_int_type(*base);
    }

   private:
    std::mutex              mutex_;
    std::condition_variable readable_;
    std::deque<std::string> queue_;
    std::string             pending_;
    bool                    closed_ = false;
};

// ------------------------------------------------------------ 输出逐行收集
class LineSink: public std::streambuf {
   public:
    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        lines_.clear();
        partial_.clear();
    }

    std::size_t mark() {
        std::lock_guard<std::mutex> lock(mutex_);
        return lines_.size();
    }

    // 从 start 起等待第一行以 prefix 开头；超时返回 false。
    bool waitForPrefix(std::size_t start, const std::string& prefix, int timeoutMs) {
        std::unique_lock<std::mutex> lock(mutex_);
        const auto                   deadline = std::chrono::steady_clock::now()
                             + std::chrono::milliseconds(timeoutMs);
        while (true) {
            for (std::size_t i = start; i < lines_.size(); ++i)
                if (lines_[i].compare(0, prefix.size(), prefix) == 0)
                    return true;
            if (writable_.wait_until(lock, deadline) == std::cv_status::timeout)
                return false;
        }
    }

    std::vector<std::string> snapshot() {
        std::lock_guard<std::mutex> lock(mutex_);
        return lines_;
    }

   protected:
    std::streamsize xsputn(const char* data, std::streamsize size) override {
        append(data, static_cast<std::size_t>(size));
        return size;
    }

    int_type overflow(int_type ch) override {
        if (!traits_type::eq_int_type(ch, traits_type::eof())) {
            const char c = traits_type::to_char_type(ch);
            append(&c, 1);
        }
        return traits_type::not_eof(ch);
    }

   private:
    void append(const char* data, std::size_t size) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            for (std::size_t i = 0; i < size; ++i) {
                const char c = data[i];
                if (c == '\n' || c == '\r') {
                    if (!partial_.empty()) {
                        lines_.push_back(partial_);
                        partial_.clear();
                    }
                } else if (partial_.size() < 8192) {  // 单行封顶，防止失控输出吃内存
                    partial_.push_back(c);
                }
            }
        }
        writable_.notify_all();
    }

    std::mutex               mutex_;
    std::condition_variable  writable_;
    std::vector<std::string> lines_;
    std::string              partial_;
};

// ------------------------------------------------------------------- 会话
struct PikafishSession {
    LineFeeder  feeder;
    LineSink    sink;
    std::thread worker;

    std::mutex        callMutex;  // analyze 串行化（引擎一次只跑一个搜索）
    std::streambuf*   savedCin  = nullptr;
    std::streambuf*   savedCout = nullptr;
    std::streambuf*   savedCerr = nullptr;
    std::atomic<bool> running{false};
    int               threads = 0;
    int               hashMb = 0;
    std::string       evalFile;
};

std::mutex                    g_stateMutex;  // 保护会话的建立/销毁（quit 需要等搜索结束）
std::atomic<PikafishSession*> g_session{nullptr};

void runEngineLoop(PikafishSession* session) {
    // CommandLine 持有 argv 指针，生命周期必须覆盖 UCIEngine 全程。
    std::vector<std::string> argStore{"pikafish"};
    std::vector<char*>       argv{argStore[0].data(), nullptr};

    Attacks::init();
    Position::init();

    auto uci = std::make_unique<UCIEngine>(CommandLine(1, argv.data()));
    Tune::init(uci->engine_options());
    uci->loop();

    session->running = false;
}

void sendOption(PikafishSession* session, const char* name, const std::string& value) {
    session->feeder.pushLine(std::string("setoption name ") + name + " value " + value);
}

// 必须在 g_stateMutex 持有时调用。
void destroySessionLocked() {
    PikafishSession* session = g_session.exchange(nullptr);
    if (session == nullptr)
        return;

    session->feeder.pushLine("quit");
    session->feeder.close();
    if (session->worker.joinable())
        session->worker.join();

    std::cin.rdbuf(session->savedCin);
    std::cout.rdbuf(session->savedCout);
    std::cerr.rdbuf(session->savedCerr);
    delete session;
}

bool startSession(JNIEnv* env, jstring evalPath, jint threads, jint hashMb) {
    std::lock_guard<std::mutex> guard(g_stateMutex);
    const int         requestedThreads = threads > 0 ? threads : 2;
    const int         requestedHashMb  = hashMb > 0 ? hashMb : 64;
    const std::string requestedEvalFile = jstringToUtf8(env, evalPath);
    if (PikafishSession* active = g_session.load()) {
        // worker 每次算棋都可能来自新的 QuickJS 上下文；不能因为会话已存在就吞掉新参数。
        // g_stateMutex 同时包住 analyze，确保 setoption 在两次搜索之间执行。
        if (active->threads == requestedThreads && active->hashMb == requestedHashMb
            && active->evalFile == requestedEvalFile)
            return true;
        const std::size_t mark = active->sink.mark();
        if (active->threads != requestedThreads)
            sendOption(active, "Threads", std::to_string(requestedThreads));
        if (active->hashMb != requestedHashMb)
            sendOption(active, "Hash", std::to_string(requestedHashMb));
        if (active->evalFile != requestedEvalFile && !requestedEvalFile.empty())
            sendOption(active, "EvalFile", requestedEvalFile);
        active->feeder.pushLine("isready");
        if (!active->sink.waitForPrefix(mark, "readyok", 180000)) {
            destroySessionLocked();
            return false;
        }
        active->threads = requestedThreads;
        active->hashMb = requestedHashMb;
        active->evalFile = requestedEvalFile;
        return true;
    }

    auto* session      = new PikafishSession();
    session->savedCin  = std::cin.rdbuf(&session->feeder);
    session->savedCout = std::cout.rdbuf(&session->sink);
    session->savedCerr = std::cerr.rdbuf(&session->sink);
    session->running   = true;

    g_session.store(session);
    session->worker = std::thread(runEngineLoop, session);

    session->feeder.pushLine("uci");
    if (!session->sink.waitForPrefix(0, "uciok", 15000)) {
        destroySessionLocked();
        return false;
    }

    sendOption(session, "Threads", std::to_string(requestedThreads));
    sendOption(session, "Hash", std::to_string(requestedHashMb));

    if (!requestedEvalFile.empty())
        sendOption(session, "EvalFile", requestedEvalFile);

    session->feeder.pushLine("isready");
    if (!session->sink.waitForPrefix(0, "readyok", 180000)) {  // NNUE 首次加载可能较慢
        destroySessionLocked();
        return false;
    }
    session->threads = requestedThreads;
    session->hashMb = requestedHashMb;
    session->evalFile = requestedEvalFile;
    return true;
}

// 返回 "info ... pv ...\n…\nbestmove ..."：所有带 pv 的 info 行 + bestmove 行。
// multiPv>1 时先设置 MultiPV，再照常搜索（上层从多行里按 multipv 序号挑候选）。
// 出错时返回 "error: ..."（上层识别前缀）。
std::string analyze(JNIEnv* env, jstring fen, jlong movetimeMs, jlong timeoutMs, jint multiPv,
                    bool fullPositionCommand) {
    std::lock_guard<std::mutex> guard(g_stateMutex);  // 整个搜索期间不许 quit
    PikafishSession*            session = g_session.load();
    if (session == nullptr)
        return "error: engine-not-started";

    std::lock_guard<std::mutex> call(session->callMutex);
    session->sink.reset();

    const int pvCount = multiPv > 1 ? static_cast<int>(multiPv) : 1;
    session->feeder.pushLine("setoption name MultiPV value " + std::to_string(pvCount));
    const std::string input = jstringToUtf8(env, fen);
    if (input.empty() || input.size() > 4096 || input.find('\n') != std::string::npos ||
        input.find('\r') != std::string::npos ||
        (fullPositionCommand && input.compare(0, 13, "position fen ") != 0))
        return "error: invalid-position-input";
    session->feeder.pushLine(fullPositionCommand ? input : "position fen " + input);
    const long movetime = movetimeMs > 0 ? static_cast<long>(movetimeMs) : 1000L;
    session->feeder.pushLine("go movetime " + std::to_string(movetime));

    const int   waitMs = timeoutMs > 0 ? static_cast<int>(timeoutMs)
                                       : static_cast<int>(movetime) + 15000;
    std::size_t mark   = session->sink.mark();
    if (!session->sink.waitForPrefix(mark, "bestmove", waitMs)) {
        session->feeder.pushLine("stop");
        if (!session->sink.waitForPrefix(mark, "bestmove", 3000))
            return "error: timeout";
    }

    std::string              bestmoveLine;
    std::vector<std::string> infoLines;
    for (const std::string& line: session->sink.snapshot()) {
        if (line.compare(0, 8, "bestmove") == 0)
            bestmoveLine = line;
        else if (line.compare(0, 5, "info ") == 0 && line.find(" pv ") != std::string::npos) {
            infoLines.push_back(line);
            if (infoLines.size() > 300)  // 单次搜索的深度行数有上限，防失控
                infoLines.erase(infoLines.begin());
        }
    }

    std::string result;
    for (const std::string& line: infoLines) {
        result += line;
        result += '\n';
    }
    result += bestmoveLine;
    return result;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_stardust_autojs_engine_PikafishNativeBridge_nativeStart(JNIEnv* env, jclass, jstring evalPath,
                                                                 jint threads, jint hashMb) {
    return startSession(env, evalPath, threads, hashMb) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_stardust_autojs_engine_PikafishNativeBridge_nativeConfiguredThreads(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_stateMutex);
    PikafishSession* session = g_session.load();
    return session != nullptr ? static_cast<jint>(session->threads) : 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_stardust_autojs_engine_PikafishNativeBridge_nativeAnalyze(JNIEnv* env, jclass, jstring fen,
                                                                   jlong movetimeMs, jlong timeoutMs,
                                                                   jint multiPv) {
    const std::string result = analyze(env, fen, movetimeMs, timeoutMs, multiPv, false);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_stardust_autojs_engine_PikafishNativeBridge_nativeAnalyzePosition(JNIEnv* env, jclass,
                                                                            jstring positionCommand,
                                                                            jlong movetimeMs, jlong timeoutMs,
                                                                            jint multiPv) {
    const std::string result = analyze(env, positionCommand, movetimeMs, timeoutMs, multiPv, true);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_stardust_autojs_engine_PikafishNativeBridge_nativeStop(JNIEnv*, jclass) {
    // 刻意不加 g_stateMutex：stop 要能在 analyze 阻塞期间打断它。
    // feeder 自身线程安全；会话销毁时先 exchange 再 join，这里的指针至少在 quit 前有效。
    PikafishSession* session = g_session.load();
    if (session != nullptr)
        session->feeder.pushLine("stop");
}

extern "C" JNIEXPORT void JNICALL
Java_com_stardust_autojs_engine_PikafishNativeBridge_nativeQuit(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_stateMutex);
    destroySessionLocked();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_stardust_autojs_engine_PikafishNativeBridge_nativeIsRunning(JNIEnv*, jclass) {
    PikafishSession* session = g_session.load();
    return (session != nullptr && session->running.load()) ? JNI_TRUE : JNI_FALSE;
}
