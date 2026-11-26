// nasty.hpp - a torture test for CppToC: the C++ that real provider headers contain. (No anonymous struct
// member with a name: jextract built with libclang 18 writes Java that does not compile for one.)
#ifndef NASTY_HPP
#define NASTY_HPP

#include <cstdint>
#include <cstddef>
#include <string>
#include <vector>

#ifdef __cplusplus
#define NASTY_BEGIN extern "C" {
#define NASTY_END }
#else
#define NASTY_BEGIN
#define NASTY_END
#endif

#define NASTY_API __attribute__((visibility("default")))
#define NASTY_DEPRECATED(msg) [[deprecated(msg)]]

namespace nasty {

constexpr int MAX_ITEMS = 8;                    // items per batch
constexpr std::uint32_t FLAGS = 1u << 4 | 0x3;
static const double SCALE = 2.5;
constexpr const char* VERSION = "1.2.3";
const int BIG = 1'000'000;
constexpr int MASK = static_cast<int>(0xFF) & ~0x0F;

enum class Color : int { Red, Green = 5, Blue };
enum class Small : std::uint8_t { A = 1, B, C };
enum Mode { MODE_OFF, MODE_ON = MAX_ITEMS + 1 };
enum class Status { OK, FAILED };              // OK clashes with nothing; FAILED neither

struct Vec2 {
    double x = 0, y = 0;                       // default member initializers
    Vec2() = default;
    Vec2(double x_, double y_) : x(x_), y{y_} {}
    double length() const { return x * x + y * y; }
    static constexpr int DIM = 2;
    static int instances;
    friend bool operator<(const Vec2&, const Vec2&);
};

typedef struct {
    std::int32_t id;
    char tag[MAX_ITEMS];
    Color color;
    Small size;
    bool active;
    union {
        int asInt;
        float asFloat;
    };
    unsigned flags : 3;
    unsigned more : 5;
    Vec2 where[Vec2::DIM];
    void (*callback)(int, const Vec2&);
    alignas(16) double aligned;
} Item;

struct Node {
    // the payload
    struct Inner { int value; } inner;
    enum Kind { LEAF, BRANCH } kind;
    Node* next;
private:
    void helper();
};

class Engine {                                 // opaque: virtual functions
public:
    virtual ~Engine();
    virtual void run() = 0;
};

class Counter {                                // opaque: private data
    int count_;
public:
    int get() const { return count_; }
};

struct Point3 : Vec2 { double z; };            // opaque: base class

struct WithString { std::string name; };       // opaque: std member

using Callback = void (*)(const Item& item, void* user);
typedef std::string Name;                       // not translatable
using Ids = std::vector<int>;                   // not translatable
typedef Vec2 Vector;

template <typename T> T clampValue(T v, T lo, T hi) { return v < lo ? lo : v > hi ? hi : v; }

NASTY_API int sum(const int* values, std::size_t count = 0) noexcept;
NASTY_API Vec2 add(Vec2 a, const Vec2& b);
NASTY_API Vec2& origin();
NASTY_API const char* describe(const Item& item);
NASTY_API Engine* createEngine(int kind);
NASTY_API void destroyEngine(Engine* engine);
NASTY_API void runEngine(Engine& engine);
NASTY_API Color mix(Color a, const Color& b);
NASTY_API Small shrink(Small s);
NASTY_API bool isActive(const Item* item);
NASTY_API void setName(const std::string& name);
NASTY_API void take(Item&& item);
NASTY_API double area(const Vec2& v);
NASTY_API double area(const Item& item);
NASTY_DEPRECATED("use area") NASTY_API double oldArea(const Vec2& v);
[[nodiscard]] NASTY_API int countItems(const Item* items, int n);
inline int twice(int x) { return 2 * x; }
NASTY_API void forEach(const Item* items, int n, Callback cb, void* user);
NASTY_API void withCallback(void (*cb)(int, const Vec2&));
NASTY_API int clampInt(int v, const int& lo, const int& hi);
NASTY_API void fill(Item items[MAX_ITEMS]);
NASTY_API int* counters();
NASTY_API Mode toggle(Mode m);
NASTY_API double length(Vector v);
NASTY_API Status check(int);

bool operator==(const Vec2& a, const Vec2& b);

namespace detail {
NASTY_API int secret(int x);
}

}  // namespace nasty

NASTY_BEGIN
NASTY_API int nasty_c_init(nasty::Item* item);
NASTY_API void nasty_c_log(const char* fmt, ...);
NASTY_API int nasty_c_version(void);
NASTY_API int nasty_c_count();
NASTY_END

extern "C" NASTY_API double nasty_c_scale(const nasty::Vec2& v, double factor = 1.0);

#ifdef NASTY_LEGACY_API
NASTY_API int nasty_legacy_only(void);
#else
NASTY_API int nasty_current_only(void);
#endif

#endif  // NASTY_HPP
