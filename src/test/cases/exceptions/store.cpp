// store.cpp - the provider's implementation for the exceptions test.
#include "store.hpp"
#include "errors.hpp"

#include <stdexcept>
#include <string>

int store_call(int kind) {
    switch (kind) {
    case 0: return 7;
    case 1: throw vendor::Error("disk full", 28);
    case 2: throw vendor::Timeout("no answer in 5 s", 110);
    case 3: throw vendor::Fault{13};
    case 4: throw vendor::Locked("store is locked");
    case 5: throw std::out_of_range("kind 5 is out of range");
    case 6: throw "a C string";
    case 7: throw std::string("a std::string");
    case 8: throw 42;
    case 9: throw 2.5;
    case 10: throw vendor::Opaque{};
    default: return -1;
    }
}
