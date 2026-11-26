// store.hpp - an API whose implementation throws every kind of C++ exception (see store.cpp).
#ifndef STORE_HPP
#define STORE_HPP

// kind 0 returns 7; 1..10 throw (vendor::Error, vendor::Timeout, vendor::Fault, vendor::Locked,
// std::out_of_range, "a C string", std::string, 42, 2.5, vendor::Opaque).
int store_call(int kind);

#endif
