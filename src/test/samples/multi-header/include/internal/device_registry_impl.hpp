#ifndef DEVICE_REGISTRY_BUILD
#error "device_registry_impl.hpp is the provider's own header, for building the library: not part of its API"
#endif
void* registryHandle();
