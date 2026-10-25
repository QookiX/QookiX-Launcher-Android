//
// Created by maks on 24.09.2022.
//

#include <stdlib.h>
#include <android/log.h>
#include <assert.h>
#include <string.h>
#include "environ.h"
#define TAG __FILE_NAME__
#include <log.h>

struct pojav_environ_s *pojav_environ;

/* 构造函数本体在下面，这里先声明以便兜底函数调用。 */
__attribute__((constructor)) void env_init(void);

/**
 * 确保 `pojav_environ` 已就绪（幂等）。
 *
 * 正常路径是下面的构造函数在 dlopen 时初始化；但实测存在它没生效的情况
 * （`JNI_OnLoad` 里 `pojav_environ->dalvikJavaVMPtr` 直接空指针崩，
 * tombstone 表现为 `fault addr 0x0`、pc 落在 `JNI_OnLoad`），
 * 而且 `POJAV_ENVIRON` 若被传成解析为 0 的字符串，构造函数也会把指针设成 NULL。
 * 所以这里给调用方一个显式兜底，加载阶段不再致命。
 */
void qookix_environ_ensure(void) {
    if (pojav_environ == NULL) env_init();
}

__attribute__((constructor)) void env_init() {
    char* strptr_env = getenv("POJAV_ENVIRON");
    if(strptr_env == NULL) {
        LOGI("No environ found, creating...");
        pojav_environ = malloc(sizeof(struct pojav_environ_s));
        assert(pojav_environ);
        memset(pojav_environ, 0 , sizeof(struct pojav_environ_s));
        if(asprintf(&strptr_env, "%p", pojav_environ) == -1) abort();
        setenv("POJAV_ENVIRON", strptr_env, 1);
        free(strptr_env);
    }else{
        LOGI("Found existing environ: %s", strptr_env);
        pojav_environ = (void*) strtoul(strptr_env, NULL, 0x10);
    }
    LOGI("%p", pojav_environ);
}