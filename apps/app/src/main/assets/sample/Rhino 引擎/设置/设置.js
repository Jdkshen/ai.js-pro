// @engine rhino
// ⚠ 需要 Auto.js Pro 专属 API：$settings 应用设置模块（当前引擎暂未实现，直接运行会报错）
// 打印一系列的设置开关是否打开
log('稳定模式: ' + $settings.isEnabled('stable_mode'))
log('使用Root启用无障碍服务: ' + $settings.isEnabled('enable_accessibility_service_by_root'))
log('音量上键停止所有脚本: ' + $settings.isEnabled('stop_all_on_volume_up'))
log('启动时不显示日志界面: ' + $settings.isEnabled('not_show_console'))
log('前台服务: ' + $settings.isEnabled('foreground_service'))

// 启用稳定模式
$settings.setEnabled('stable_mode', true);

// 关闭前台服务
$settings.setEnabled('foreground_service', false);