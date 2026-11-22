#ifndef MIPLAY_CORE_H
#define MIPLAY_CORE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* 释放所有 miplay_* 返回的 UTF-8 堆字符串；允许传入 NULL。 */
void miplay_string_free(char *ptr);

/* 发现局域网设备并融合本地缓存，返回结构化 JSON（rows / merged / cacheable）。 */
char *miplay_discover_json(const char *local_ip, int32_t timeout_ms, const char *cached_json);

/* 发现局域网设备并按需并发只读查 8899 miName；force_refresh_names=true 用于手动刷新重查。 */
char *miplay_discover_json_ex(
    const char *local_ip,
    int32_t timeout_ms,
    const char *cached_json,
    bool force_refresh_names
);

/* 发现局域网设备，返回 6 列 TSV。 */
char *miplay_discover_tsv(const char *local_ip, int32_t timeout_ms);

/* 跨端共享路由状态机：action = "select_this" | "check" | "uncheck" | "merge_discovered"。 */
char *miplay_route_apply_json(const char *devices_json, const char *action, const char *target);

/* Wi-Fi 频段（MHz，<=0 未知）→ 推荐播放缓冲延迟（ms）。 */
int32_t miplay_delay_for_frequency(int32_t mhz);

/* 根据目标播放延迟（ms，<=0 表示标准自动档）返回冷启动预热帧数（AU）。 */
int32_t miplay_startup_runway_aus(int32_t delay_ms);

/* 认证模式字符串解析："auto"->0, "legacy"->1, "safety"->2, 未知->-1。 */
int32_t miplay_parse_auth_mode(const char *raw);

/* 设置全局认证模式（0=auto, 1=legacy, 2=safety），对后续 connect 生效。 */
void miplay_set_auth_mode(int32_t mode);

/* 创建投送引擎实例并返回非零句柄；silent=true 使用内置静音 AAC 帧。 */
int64_t miplay_engine_create(bool silent);

/* 阻塞连接一台或多台音箱（ips 逗号分隔），返回初始状态 JSON。 */
char *miplay_engine_connect(
    int64_t handle,
    const char *ips,
    int32_t delay_ms,
    int32_t premute,
    int32_t volume_after_play,
    const char *ref_function,
    const char *source_name
);

/* 推入一帧 48kHz 立体声裸 AAC AU（不含 ADTS 头）。返回 0 成功，负数为错误码。 */
int32_t miplay_engine_push_aac(int64_t handle, const uint8_t *data, size_t len);

/* 协调式增删接收端与调音量（支持在首台建连期间排队并自动 reconcile）。 */
char *miplay_engine_add_receiver(int64_t handle, const char *ip);
bool miplay_engine_remove_receiver(int64_t handle, const char *ip);
char *miplay_engine_set_volume(int64_t handle, const char *ip, int32_t volume);

/* 播放期组控制。 */
char *miplay_engine_pause(int64_t handle);
char *miplay_engine_resume(int64_t handle);
char *miplay_engine_switch_source(int64_t handle);
/* 运行期热更新播放缓冲延迟（delay_ms <= 0 按频段自动；重锚并广播 TIME_OFFSET，无需重连）。 */
char *miplay_engine_set_playback_delay(int64_t handle, int32_t delay_ms);

/* 拉取实时统计 JSON（每台设备均含归一化 ui_state 与 display_latency_ms）。 */
char *miplay_engine_stats_json(int64_t handle);

/* 停止投送并释放引擎句柄（无死锁清理）。 */
void miplay_engine_stop(int64_t handle);

#ifdef __cplusplus
}
#endif

#endif /* MIPLAY_CORE_H */
