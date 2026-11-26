#include "device_registry_types.hpp"

// One entry point for the registry; *outPtr receives the library's internal buffer (a log of
// the last call, NUL-terminated) and *sizeOfInternalBuffer its size.
// Throws std::out_of_range for an unknown reqType.
 void updateRegistry(int reqType,
                           Camera& camera,
                           Lidar& lidar,
                           Radar& radar,
                           double timeTag,
                           double pendingEvents,
                           void* ptr,
                           void** outPtr,
                           Camera* cameras,
                           Lidar* lidars,
                           Radar* radars,
                           int* sizeOfInternalBuffer,
                           double* cameraCount,
                           double* lidarsCount);