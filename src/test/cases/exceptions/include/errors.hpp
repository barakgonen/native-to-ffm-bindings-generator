// errors.hpp - the provider's exception types, in a header the API header does not include (as SDKs often do).
#ifndef ERRORS_HPP
#define ERRORS_HPP

#include <stdexcept>
#include <string>

namespace vendor {

// not a std::exception: the safe wrapper reports its message only if it is mapped (--exceptions)
class Error {
public:
    Error(std::string message, int code) : message_(std::move(message)), code_(code) {}
    const std::string& message() const { return message_; }
    int code() const { return code_; }
private:
    std::string message_;
    int code_;
};

class Timeout : public Error {           // derived: listed before Error in --exceptions
public:
    using Error::Error;
};

struct Fault {                           // mapped by a field
    int code;
};

class Locked : public std::runtime_error {   // a std::exception: reported with its own type name, unmapped
public:
    using std::runtime_error::runtime_error;
};

struct Opaque {};                        // not mapped and not a std::exception: reported by type only

}  // namespace vendor

#endif
