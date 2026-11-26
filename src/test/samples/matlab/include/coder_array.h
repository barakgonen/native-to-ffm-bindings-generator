/* Copyright 2019-2023 The MathWorks, Inc. */
#ifndef _mw_coder_array_h
#define _mw_coder_array_h

#include <cassert>
#include <cstring>
#include <iterator>
#include <string>
#include <vector>

namespace coder {

template <typename T, typename SZ, int N>
class array_base {
  public:
    array_base() : data_(nullptr), owner_(true) {}
    T& operator[](SZ index) { return data_[index]; }
    SZ size(int i) const { return size_[i]; }
    void set_size(SZ m, SZ n) { size_[0] = m; size_[1] = n; }
  protected:
    T* data_;
    SZ size_[N];
    bool owner_;
};

template <typename T, int N>
class array : public array_base<T, int, N> {
  public:
    array() : array_base<T, int, N>() {}
};

}  // namespace coder

#endif
