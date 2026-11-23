#ifndef MIAOCAST_CORE_H
#define MIAOCAST_CORE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* 释放所有 miaocast_* 返回的 UTF-8 堆字符串；允许传入 NULL。 */
void miaocast_string_free(char *ptr);

/* 发现局域网设备并融合本地缓存，返回结构化 JSON（rows / merged / cacheable）。 */
char *miaocast_discover_json(const char *local_ip, int32_t timeout_ms, const char *cached_json);

/* 发现局域网设备并按需并发只读查 8899 miName/roomName；force_refresh_names=true 用于手动刷新重查。 */
char *miaocast_discover_json_ex(
    const char *local_ip,
    int32_t timeout_ms,
    const char *cached_json,
    bool force_refresh_names
);

/* 发现局域网设备，返回 6 列 TSV。 */
char *miaocast_discover_tsv(const char *local_ip, int32_t timeout_ms);

/* 跨端共享路由状态机：action = "select_this" | "check" | "uncheck" | "merge_discovered"。 */
char *miaocast_route_apply_json(const char *devices_json, const char *action, const char *target);

/* Wi-Fi 频段（MHz，<=0 未知）→ 推荐播放缓冲延迟（ms）。 */
int32_t miaocast_delay_for_frequency(int32_t mhz);

/* 根据目标播放延迟（ms，<=0 表示标准自动档）返回冷启动预热帧数（AU）。 */
int32_t miaocast_startup_runway_aus(int32_t delay_ms);

/* 认证模式字符串解析："auto"->0, "legacy"->1, "safety"->2, 未知->-1。 */
int32_t miaocast_parse_auth_mode(const char *raw);

/* 设置全局认证模式（0=auto, 1=legacy, 2=safety），对后续 connect 生效。 */
void miaocast_set_auth_mode(int32_t mode);

/* 设置全局音量过大保护策略（默认开启、上限 60%，合法范围 30..=90）。 */
void miaocast_set_volume_guard_policy(bool enabled, int32_t safe_limit);

/* 裁决一次音量变更是否需要二次确认（仅在跨过安全上限线时返回 require_confirm JSON）。 */
char *miaocast_volume_guard_evaluate_json(
    bool enabled,
    int32_t safe_limit,
    int32_t current_volume,
    int32_t target_volume
);

/* 创建投送引擎实例并返回非零句柄；silent=true 使用内置静音 AAC 帧。 */
int64_t miaocast_engine_create(bool silent);

/* 阻塞连接一台或多台音箱（ips 逗号分隔），返回初始状态 JSON。 */
char *miaocast_engine_connect(
    int64_t handle,
    const char *ips,
    int32_t delay_ms,
    int32_t premute,
    int32_t volume_after_play,
    const char *ref_function,
    const char *source_name
);

/* 同上，附带 volume_confirmed 标志：用户已确认超限初始音量时传 true，否则按安全上限兜底钳制。 */
char *miaocast_engine_connect_ex(
    int64_t handle,
    const char *ips,
    int32_t delay_ms,
    int32_t premute,
    int32_t volume_after_play,
    const char *ref_function,
    const char *source_name,
    bool volume_confirmed
);

/* 推入一帧 48kHz 立体声裸 AAC AU（不含 ADTS 头）。返回 0 成功，负数为错误码。 */
int32_t miaocast_engine_push_aac(int64_t handle, const uint8_t *data, size_t len);

/* 协调式增删接收端与调音量（支持在首台建连期间排队并自动 reconcile）。 */
char *miaocast_engine_add_receiver(int64_t handle, const char *ip);
bool miaocast_engine_remove_receiver(int64_t handle, const char *ip);
char *miaocast_engine_set_volume(int64_t handle, const char *ip, int32_t volume);
/* 带二次确认标志的协调式调音量：confirmed=true 放行已确认超限值，false 时按安全上限兜底钳制。 */
char *miaocast_engine_set_volume_checked(
    int64_t handle,
    const char *ip,
    int32_t volume,
    bool confirmed
);

/* 播放期组控制。 */
char *miaocast_engine_pause(int64_t handle);
char *miaocast_engine_resume(int64_t handle);
char *miaocast_engine_switch_source(int64_t handle);
/* 运行期热更新播放缓冲延迟（delay_ms <= 0 按频段自动；重锚并广播 TIME_OFFSET，无需重连）。 */
char *miaocast_engine_set_playback_delay(int64_t handle, int32_t delay_ms);

/* 拉取实时统计 JSON（每台设备均含归一化 ui_state 与 display_latency_ms）。 */
char *miaocast_engine_stats_json(int64_t handle);

/* 停止投送并释放引擎句柄（无死锁清理）。 */
void miaocast_engine_stop(int64_t handle);

/* 比较两个版本号字符串（自动剥离前导 v 与 -release/-acceptance/-debug 后缀）。
 * 返回值：-1 (a < b), 0 (a == b), 1 (a > b), -2 (格式非法)。 */
int32_t miaocast_compare_versions(const char *a, const char *b);

/* 跨端更新裁决引擎：解析 update.json 或 GitHub Releases API JSON，
 * 返回归一化决策 JSON（含 (current_version, target_version] 跨版本区间日志）。 */
char *miaocast_update_evaluate_json(
    const char *current_version,
    const char *channel,
    const char *platform,
    const char *abi,
    const char *os_version,
    const char *ignored_tag,
    const char *raw_response_json
);

#ifdef __cplusplus
}
#endif

#endif /* MIAOCAST_CORE_H */
