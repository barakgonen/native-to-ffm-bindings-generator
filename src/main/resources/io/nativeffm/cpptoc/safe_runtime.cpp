// safe_runtime.cpp - the part of every generated <name>_safe.cpp that does not depend on the headers:
// error codes, the thread-local error details, exception type names, and the helpers the wrappers call.
// SafeWrapper.java copies everything below the marker line into the generated file, after the provider's
// headers and SAFE_CATCH_MAPPED_EXCEPTIONS (the catch clauses for the types in --exceptions, if any).
// ---- copied from here ----
#include <cstddef>
#include <cstdio>
#include <exception>
#include <sstream>
#include <string>
#include <typeinfo>
#include <cstdlib>
#include <cxxabi.h>

// Linux, GCC or Clang. The safe wrapper wraps every function, deprecated ones too.
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"

#define SAFE_API extern "C" __attribute__((visibility("default")))

#define SAFE_OK 0
#define SAFE_NULL_ARGUMENT 1
#define SAFE_CPP_EXCEPTION 2
#define SAFE_UNKNOWN_EXCEPTION 3

#ifndef SAFE_CATCH_MAPPED_EXCEPTIONS
#define SAFE_CATCH_MAPPED_EXCEPTIONS
#endif

namespace safe_detail {

thread_local std::string error_function, error_type, error_message;

int fail(int code, const char* function, const char* type, const char* message) noexcept {
    try {
        error_function = function;
        error_type = type;
        error_message = message;
    } catch (...) {  // out of memory while recording the details: keep the code
        error_type.clear();
        error_message.clear();
    }
    return code;
}

int fail(int code, const char* function, const std::string& type, const std::string& message) noexcept {
    return fail(code, function, type.c_str(), message.c_str());
}

std::string type_name(const std::type_info& type) {
    int status = 0;
    char* name = abi::__cxa_demangle(type.name(), nullptr, nullptr, &status);
    if (status == 0 && name != nullptr) {
        std::string result(name);
        std::free(name);
        return result;
    }
    return type.name();
}

// The message of a mapped exception: whatever its member returns (std::string, const char*, a number, or
// anything with an operator<<).
inline std::string text(const char* s) { return s != nullptr ? s : ""; }
inline std::string text(const std::string& s) { return s; }
template <typename T>
std::string text(const T& value) {
    std::ostringstream out;
    out << value;
    return out.str();
}

std::string current_exception_type() {
    if (const std::type_info* type = abi::__cxa_current_exception_type()) return type_name(*type);
    return "unknown";
}

// Called in catch (...): maps the exception in flight to a status code and records its details. Every exception
// is reported: a mapped one (a type in --exceptions, or a std::exception) with its type and message as
// SAFE_CPP_EXCEPTION; anything else as SAFE_UNKNOWN_EXCEPTION, with its type and, for a thrown string or number,
// its value. The type is the exception's dynamic type (a subclass of std::runtime_error has its own name).
int caught(const char* function) noexcept {
    try {
        try {
            throw;
        }
        SAFE_CATCH_MAPPED_EXCEPTIONS
        catch (const std::exception& e) {
            return fail(SAFE_CPP_EXCEPTION, function, type_name(typeid(e)), e.what());
        } catch (const char* s) {                                   // throw "disk full";
            return fail(SAFE_UNKNOWN_EXCEPTION, function, "const char*", text(s));
        } catch (const std::string& s) {
            return fail(SAFE_UNKNOWN_EXCEPTION, function, "std::string", s);
        } catch (int v) {                                           // throw 42; throw ERROR_CODE;
            return fail(SAFE_UNKNOWN_EXCEPTION, function, "int", std::to_string(v));
        } catch (long v) {
            return fail(SAFE_UNKNOWN_EXCEPTION, function, "long", std::to_string(v));
        } catch (long long v) {
            return fail(SAFE_UNKNOWN_EXCEPTION, function, "long long", std::to_string(v));
        } catch (unsigned v) {
            return fail(SAFE_UNKNOWN_EXCEPTION, function, "unsigned int", std::to_string(v));
        } catch (unsigned long v) {
            return fail(SAFE_UNKNOWN_EXCEPTION, function, "unsigned long", std::to_string(v));
        } catch (unsigned long long v) {
            return fail(SAFE_UNKNOWN_EXCEPTION, function, "unsigned long long", std::to_string(v));
        } catch (double v) {
            return fail(SAFE_UNKNOWN_EXCEPTION, function, "double", text(v));
        } catch (...) {                                             // any other type: its name, no message
            return fail(SAFE_UNKNOWN_EXCEPTION, function, current_exception_type(), "");
        }
    } catch (...) {  // out of memory while recording the details
        return fail(SAFE_UNKNOWN_EXCEPTION, function, "", "");
    }
}

int null_argument(const char* function, const char* argument, const char* what) noexcept {
    char message[256];
    std::snprintf(message, sizeof message, "argument '%s' is NULL (%s)", argument, what);
    return fail(SAFE_NULL_ARGUMENT, function, "", message);
}

}  // namespace safe_detail
