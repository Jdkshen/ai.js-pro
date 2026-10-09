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
//   nativeOptions ──►  返回启动时从 "uci" 回复抓到的 "option name ..." 行（自检用）
//
// analyze 返回 "最后一条带 pv 的 info 行\n bestmove 行"（原样两行，分数/PV 解析交给上层，
// 避免在 C++ 里重复实现 UCI 语法解析）。

#include <jni.h>
#include <android/log.h>
#include <unistd.h>

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

// ---- JNI 导出名：同一份胶水服务两条构建 ----
//   默认（宿主随包）：Java_com_stardust_autojs_engine_PikafishNativeBridge_*
//   象棋插件 APK（plugins/xiangqi-plugin）：编到自己的 libxiangqi_engine.so，
//   用 -DPIKAFISH_JNI_CLASS=Java_com_jdkshen_xiangqi_plugin_NativeEngine 换成插件自己的桥接类。
// 这样插件的引擎不必复制一份 JNI 胶水，两边永远同源。
#ifndef PIKAFISH_JNI_CLASS
#define PIKAFISH_JNI_CLASS Java_com_stardust_autojs_engine_PikafishNativeBridge
#endif

#define PIKAFISH_JNI_PASTE_(a, b) a##b
#define PIKAFISH_JNI_PASTE(a, b) PIKAFISH_JNI_PASTE_(a, b)
#define PIKAFISH_JNI_NAME(name) PIKAFISH_JNI_PASTE(PIKAFISH_JNI_CLASS, PIKAFISH_JNI_PASTE(_, name))

namespace {

using namespace Stockfish;

constexpr const char* kLogTag = "PikafishJNI";

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
    int               contempt = 0;  // 自编译补丁选项：和棋节点对根方记 -C（正数 = 避和）
    std::string       evalFile;
    // 启动时从 "uci" 回复抓到的 "option name ..." 行（nativeOptions 自检用）
    std::vector<std::string> options;
};

std::mutex                    g_stateMutex;  // 保护会话的建立/销毁（quit 需要等搜索结束）
/**
 * 会话指针的「读侧」保护（2026-09-26 真机 SIGABRT 修复）。
 *
 * 背景：`nativeStop` / `nativeIsRunning` 刻意不持 g_stateMutex（stop 要能在 analyze 阻塞期间
 * 打断它），但销毁线程 `delete session` 之后它们仍可能触碰旧指针 —— 真机 08:40 崩在
 * `FORTIFY: pthread_mutex_lock called on a destroyed mutex`（SIGABRT，无墓碑）。
 * 规则：凡「无锁读 g_session 指针并使用」的地方 → 持 g_ptrMutex；
 *      `delete` 之前 → 过同一把锁（等正在使用旧指针的人退出临界区）。
 * 锁序：g_stateMutex → feeder/sink 内部锁 → g_ptrMutex → delete（单向，无环）。
 */
std::mutex                    g_ptrMutex;
std::atomic<PikafishSession*> g_session{nullptr};
// analyze 在 g_stateMutex 下串行；stop 不能拿这把锁，因此用原子 TID 判定归属。
std::atomic<int> g_activeAnalyzeTid{0};

// 引擎选项表在单次构建内是常量：跨会话缓存一份。
// （真机实测：quit 再重建的会话里，启动时抓不到 "option name ..." 行——
//   首次会话能抓到；缓存兑底保证 _nativeOptions 任何时候都能给出清单。）
std::vector<std::string> g_cachedOptions;

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

    // ★ 先「停搜索 + 等 idle」再 quit（2026-09-26 真机 SIGABRT 修复之一）：
    //   analyze 超时返回时搜索可能还在跑（bestmove 没人收）或 stop 刚发出；直接 quit 后
    //   引擎线程退出、线程池对象（含 mutex/cv）析构 —— 而搜索线程可能稍后才醒，撞上
    //   「已销毁的 mutex」（bionic FORTIFY → SIGABRT）。isready 的 readyok 回复 = 引擎空闲。
    {
        const std::size_t idleMark = session->sink.mark();
        session->feeder.pushLine("stop");
        session->feeder.pushLine("isready");
        if (!session->sink.waitForPrefix(idleMark, "readyok", 8000))
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "destroy: engine busy (no readyok in 8s), quit anyway");
    }

    session->feeder.pushLine("quit");
    session->feeder.close();
    if (session->worker.joinable())
        session->worker.join();

    std::cin.rdbuf(session->savedCin);
    std::cout.rdbuf(session->savedCout);
    std::cerr.rdbuf(session->savedCerr);
    {
        // 等所有「正在用旧指针」的 stop/isRunning 退出临界区之后再 delete
        std::lock_guard<std::mutex> ptrGuard(g_ptrMutex);
    }
    delete session;
}

bool startSession(JNIEnv* env, jstring evalPath, jint threads, jint hashMb, jint contempt) {
    std::lock_guard<std::mutex> guard(g_stateMutex);
    const int         requestedThreads  = threads > 0 ? threads : 2;
    const int         requestedHashMb   = hashMb > 0 ? hashMb : 64;
    const int         requestedContempt = contempt < -200 ? -200 : (contempt > 200 ? 200 : contempt);
    const std::string requestedEvalFile = jstringToUtf8(env, evalPath);
    if (PikafishSession* active = g_session.load()) {
        // worker 每次算棋都可能来自新的 QuickJS 上下文；不能因为会话已存在就吞掉新参数。
        // g_stateMutex 同时包住 analyze，确保 setoption 在两次搜索之间执行。
        if (active->threads == requestedThreads && active->hashMb == requestedHashMb
            && active->contempt == requestedContempt && active->evalFile == requestedEvalFile)
            return true;
        // ★ 改参数前先等引擎 idle（2026-09-26）：上一次 analyze 超时后搜索可能还在跑，
        //   此时 setoption 会被主循环忽略（搜索中不读命令行）→ 参数静默不生效。
        {
            const std::size_t idleMark = active->sink.mark();
            active->feeder.pushLine("stop");
            active->feeder.pushLine("isready");
            if (!active->sink.waitForPrefix(idleMark, "readyok", 8000))
                __android_log_print(ANDROID_LOG_WARN, kLogTag, "restart: engine busy (no readyok in 8s)");
        }
        const std::size_t mark = active->sink.mark();
        if (active->threads != requestedThreads)
            sendOption(active, "Threads", std::to_string(requestedThreads));
        if (active->hashMb != requestedHashMb)
            sendOption(active, "Hash", std::to_string(requestedHashMb));
        if (active->contempt != requestedContempt)
            sendOption(active, "Contempt", std::to_string(requestedContempt));
        if (active->evalFile != requestedEvalFile && !requestedEvalFile.empty())
            sendOption(active, "EvalFile", requestedEvalFile);
        active->feeder.pushLine("isready");
        if (!active->sink.waitForPrefix(mark, "readyok", 180000)) {
            destroySessionLocked();
            return false;
        }
        active->threads = requestedThreads;
        active->hashMb = requestedHashMb;
        active->contempt = requestedContempt;
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

    // 选项清单在 "uci" 回复里（uciok 之前）：抓下来供 nativeOptions 做真机自检
    // （例如确认自编译补丁项 Contempt 已注册）。
    const std::vector<std::string> uciLines = session->sink.snapshot();
    for (const std::string& line: uciLines) {
        if (line.compare(0, 12, "option name ") == 0)
            session->options.push_back(line);
    }
    if (!session->options.empty())
        g_cachedOptions = session->options;
    // 真机实测（2026-09-25）：同一进程里重建的会话（quit 后 start），“uci” 回复里**没有 option 行**
    // ——上游 `OptionsMap::add` 的 `insert_order` 是函数级 static，跨实例累计：第二个引擎的选项
    // idx 从 12 起，而 `operator<<` 按 0..size-1 找 idx ⇒ 打印为空（setoption 按名字查，不受影响）。
    // 所以这里用进程级缓存兑底；nativeOptions 在连缓存也没有时才向引擎重问一次。
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "start: sink lines=%zu options=%zu cache=%zu",
                        uciLines.size(), session->options.size(), g_cachedOptions.size());

    sendOption(session, "Threads", std::to_string(requestedThreads));
    sendOption(session, "Hash", std::to_string(requestedHashMb));
    if (requestedContempt != 0)  // 默认 0 时不发，保持既有启动序列不变
        sendOption(session, "Contempt", std::to_string(requestedContempt));

    if (!requestedEvalFile.empty())
        sendOption(session, "EvalFile", requestedEvalFile);

    session->feeder.pushLine("isready");
    if (!session->sink.waitForPrefix(0, "readyok", 180000)) {  // NNUE 首次加载可能较慢
        destroySessionLocked();
        return false;
    }
    session->threads = requestedThreads;
    session->hashMb = requestedHashMb;
    session->contempt = requestedContempt;
    session->evalFile = requestedEvalFile;
    return true;
}

bool isFenPieceChar(char c) {
    switch (c) {
        case 'r': case 'n': case 'b': case 'a': case 'k': case 'c': case 'p':
        case 'R': case 'N': case 'B': case 'A': case 'K': case 'C': case 'P':
            return true;
        default:
            return false;
    }
}

/**
 * 完整 FEN 的入口校验（`$pikafish.analyze` 用；六段：棋盘段 w|b - - half full，
 * 棋盘段 10 行 × 每行宽 9，数字与兵种字母都计入宽度）。
 *
 * <p>为什么必须拦：只给棋盘段（缺 " w - - 0 1"）会让引擎解析非法局面把整个进程带走
 * （2026-09-25 真机实测：无墓碑/ANR/FATAL）。Java 侧（QuickJsHostBridge.pikafishAnalyze）
 * 另有一道同规则校验，这里 fail-safe 再拦一次。
 */
bool looksLikeCompleteFen(const std::string& fen) {
    const std::size_t firstSpace = fen.find(' ');
    if (firstSpace == std::string::npos || firstSpace == 0)
        return false;
    int rows = 1;
    int width = 0;
    for (std::size_t i = 0; i < firstSpace; ++i) {
        const char c = fen[i];
        if (c == '/') {
            if (width != 9)
                return false;
            ++rows;
            width = 0;
        } else if (c >= '1' && c <= '9') {
            width += c - '0';
        } else if (isFenPieceChar(c)) {
            width += 1;
        } else {
            return false;
        }
    }
    if (rows != 10 || width != 9)
        return false;

    std::vector<std::string> rest;                  // w|b / - / - / half / full
    std::size_t start = firstSpace + 1;
    while (start <= fen.size()) {
        std::size_t end = fen.find(' ', start);
        if (end == std::string::npos)
            end = fen.size();
        if (end == start)
            return false;                           // 连续空格 / 尾随空格
        rest.emplace_back(fen.substr(start, end - start));
        start = end + 1;
    }
    if (rest.size() != 5)
        return false;
    if (rest[0] != "w" && rest[0] != "b")
        return false;
    if (rest[1] != "-" || rest[2] != "-")
        return false;
    const std::string& half = rest[3];
    const std::string& full = rest[4];
    if (half.empty() || full.empty() || full[0] == '0')
        return false;
    for (std::size_t i = 0; i < half.size(); ++i)
        if (half[i] < '0' || half[i] > '9')
            return false;
    for (std::size_t i = 0; i < full.size(); ++i)
        if (full[i] < '0' || full[i] > '9')
            return false;
    return true;
}

// 返回 "info ... pv ...\n…\nbestmove ..."：所有带 pv 的 info 行 + bestmove 行。
// multiPv>1 时先设置 MultiPV，再照常搜索（上层从多行里按 multipv 序号挑候选）。
// 出错时返回 "error: ..."（上层识别前缀）。
std::string analyze(JNIEnv* env, jstring fen, jlong movetimeMs, jlong timeoutMs, jint multiPv, jint depth,
                    bool fullPositionCommand) {
    std::lock_guard<std::mutex> guard(g_stateMutex);  // 整个搜索期间不许 quit
    PikafishSession*            session = g_session.load();
    if (session == nullptr)
        return "error: engine-not-started";

    // ⚠️ 输入校验必须放在动会话之前：非法输入直接回错误串，**绝不喂给引擎**——
    //    引擎解析非法局面（例如缺 " w - - 0 1" 的裸 FEN）会把整个 App 进程带走
    //    （2026-09-25 真机实测：无墓碑 / 无 ANR / 无 FATAL）。
    const std::string input = jstringToUtf8(env, fen);
    if (input.empty() || input.size() > 4096 || input.find('\n') != std::string::npos ||
        input.find('\r') != std::string::npos)
        return "error: invalid-position-input";
    if (fullPositionCommand) {
        if (input.compare(0, 13, "position fen ") != 0)
            return "error: invalid-position-input";
        const std::size_t movesAt = input.find(" moves");
        const std::string fenPart = input.substr(13, (movesAt == std::string::npos
                ? input.size() : movesAt) - 13);
        if (!looksLikeCompleteFen(fenPart))
            return "error: invalid-fen";
    } else if (!looksLikeCompleteFen(input)) {
        return "error: invalid-fen";
    }

    std::lock_guard<std::mutex> call(session->callMutex);

    // ★ 新搜索前先把引擎拉回 idle（2026-09-26 真机 SIGABRT 修复之二）：
    //   上一次 analyze 超时返回（bestmove 没等到）时引擎可能还在搜/还没退干净，
    //   此时直接发 "go" 会被主循环忽略（搜索中不读命令行）→ 新搜索等不到 bestmove、
    //   而旧搜索迟到的 bestmove 又会被当成新搜索的结果（张冠李戴的错着法）。
    //   UCI 语义：isready 回复 readyok = 引擎空闲。等不到就明确报错，让上层重试/重建。
    {
        const std::size_t idleMark = session->sink.mark();
        session->feeder.pushLine("stop");
        session->feeder.pushLine("isready");
        if (!session->sink.waitForPrefix(idleMark, "readyok", 8000)) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "analyze: engine busy (no readyok in 8s) -> error");
            return "error: engine-busy";
        }
    }

    session->sink.reset();

    const int pvCount = multiPv > 1 ? static_cast<int>(multiPv) : 1;
    session->feeder.pushLine("setoption name MultiPV value " + std::to_string(pvCount));
    session->feeder.pushLine(fullPositionCommand ? input : "position fen " + input);
    const long movetime = movetimeMs > 0 ? static_cast<long>(movetimeMs) : 1000L;
    const int searchDepth = depth > 0 ? (depth > 40 ? 40 : static_cast<int>(depth)) : 0;
    std::size_t mark = session->sink.mark();
    // 从 go 到 bestmove 的窗口才算活跃搜索。局部守卫覆盖超时等所有返回路径。
    struct ActiveSearchGuard {
        ~ActiveSearchGuard() { g_activeAnalyzeTid.store(0); }
    } activeSearch;
    {
        // 与 nativeStopForTid 使用同一把锁，保证 stop 永远排在 go 之后入队。
        std::lock_guard<std::mutex> ptrGuard(g_ptrMutex);
        g_activeAnalyzeTid.store(gettid());
        session->feeder.pushLine(searchDepth > 0 ? "go depth " + std::to_string(searchDepth)
                                           : "go movetime " + std::to_string(movetime));
    }

    const int   waitMs = timeoutMs > 0 ? static_cast<int>(timeoutMs)
                                       : searchDepth > 0 ? 30000 : static_cast<int>(movetime) + 15000;
    if (!session->sink.waitForPrefix(mark, "bestmove", waitMs)) {
        session->feeder.pushLine("stop");
        if (!session->sink.waitForPrefix(mark, "bestmove", 3000)) {
            // ★ 没等到 bestmove ≠ 搜索已停（2026-09-26）：这里明确记一笔——
            //   下一次 analyze 会被开头的 idle 闸门拦住并报 engine-busy，
            //   由上层决定重建会话（nativeStart 复用分支也会先等 idle 再改参数）。
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "analyze: timeout, no bestmove after stop (search may still run)");
            return "error: timeout";
        }
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
PIKAFISH_JNI_NAME(nativeStart)(JNIEnv* env, jclass, jstring evalPath,
                               jint threads, jint hashMb, jint contempt) {
    return startSession(env, evalPath, threads, hashMb, contempt) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
PIKAFISH_JNI_NAME(nativeConfiguredThreads)(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_stateMutex);
    PikafishSession* session = g_session.load();
    return session != nullptr ? static_cast<jint>(session->threads) : 0;
}

// 启动时抓到的 UCI 选项行（每行一条 "option name ..."，换行分隔）；未启动时为空串。
// 优先用本会话的清单；抓不到（真机实测：会话重建后会遇到）就用进程级缓存；
// 连缓存也没有时，在两次搜索之间向引擎重问一次 "uci"。
extern "C" JNIEXPORT jstring JNICALL
PIKAFISH_JNI_NAME(nativeOptions)(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> guard(g_stateMutex);
    PikafishSession*          session = g_session.load();
    std::vector<std::string>  lines;
    if (session != nullptr && !session->options.empty()) {
        lines = session->options;
    } else if (!g_cachedOptions.empty()) {
        lines = g_cachedOptions;
    } else if (session != nullptr) {
        const std::size_t mark = session->sink.mark();
        session->feeder.pushLine("uci");
        if (session->sink.waitForPrefix(mark, "uciok", 5000)) {
            const std::vector<std::string> fresh = session->sink.snapshot();
            for (std::size_t i = mark; i < fresh.size(); ++i) {
                if (fresh[i].compare(0, 12, "option name ") == 0)
                    lines.push_back(fresh[i]);
            }
            if (!lines.empty())
                g_cachedOptions = lines;
        }
    }
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "options: session=%p own=%zu cache=%zu -> %zu",
                        static_cast<void*>(session),
                        session != nullptr ? session->options.size() : 0,
                        g_cachedOptions.size(), lines.size());
    std::string joined;
    for (const std::string& line: lines) {
        joined += line;
        joined += '\n';
    }
    return env->NewStringUTF(joined.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
PIKAFISH_JNI_NAME(nativeAnalyze)(JNIEnv* env, jclass, jstring fen,
                                                                   jlong movetimeMs, jlong timeoutMs,
                                                                   jint multiPv, jint depth) {
    const std::string result = analyze(env, fen, movetimeMs, timeoutMs, multiPv, depth, false);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
PIKAFISH_JNI_NAME(nativeAnalyzePosition)(JNIEnv* env, jclass,
                                                                            jstring positionCommand,
                                                                            jlong movetimeMs, jlong timeoutMs,
                                                                            jint multiPv, jint depth) {
    const std::string result = analyze(env, positionCommand, movetimeMs, timeoutMs, multiPv, depth, true);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
PIKAFISH_JNI_NAME(nativeStop)(JNIEnv*, jclass) {
    // 刻意不加 g_stateMutex：stop 要能在 analyze 阻塞期间打断它。
    // ⚠️ 但必须过 g_ptrMutex：否则「load 出指针」与「delete session」之间有一个窗口，
    //    会 lock 到已销毁的成员 mutex（真机 2026-09-26 08:40 的 SIGABRT）。
    std::lock_guard<std::mutex> ptrGuard(g_ptrMutex);
    PikafishSession* session = g_session.load();
    if (session != nullptr)
        session->feeder.pushLine("stop");
}

extern "C" JNIEXPORT jboolean JNICALL
PIKAFISH_JNI_NAME(nativeStopForTid)(JNIEnv*, jclass, jint tid) {
    if (tid <= 0)
        return JNI_FALSE;
    std::lock_guard<std::mutex> ptrGuard(g_ptrMutex);
    PikafishSession* session = g_session.load();
    if (session == nullptr || g_activeAnalyzeTid.load() != tid)
        return JNI_FALSE;
    session->feeder.pushLine("stop");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
PIKAFISH_JNI_NAME(nativeQuit)(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_stateMutex);
    destroySessionLocked();
}

extern "C" JNIEXPORT jboolean JNICALL
PIKAFISH_JNI_NAME(nativeIsRunning)(JNIEnv*, jclass) {
    // 同 nativeStop：读指针要过 g_ptrMutex（会话可能正在被销毁）
    std::lock_guard<std::mutex> ptrGuard(g_ptrMutex);
    PikafishSession* session = g_session.load();
    return (session != nullptr && session->running.load()) ? JNI_TRUE : JNI_FALSE;
}
