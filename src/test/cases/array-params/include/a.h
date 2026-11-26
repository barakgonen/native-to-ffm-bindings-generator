#define REGISTRY_CAPACITY 1000
#define REGISTRY_FULL (-1)
typedef struct { int id; double weight; } Camera;
int add_camera(const Camera *camera, Camera cameras[REGISTRY_CAPACITY]);
int registry_init(Camera cameras[], int n);
