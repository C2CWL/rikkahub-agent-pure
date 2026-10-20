package me.rerere.rikkahub.service

/**
 * 批 B — 旧「定时任务 / 工作流」自动化退役开关。
 *
 * 从本批起，旧的两条自动化线在产品层退役：设置页与工作台抽屉的入口已移除，执行面
 * （[CronJobScheduler]、[CronJobWorker]、[me.rerere.rikkahub.workflow.execution.WorkflowEngine]）
 * 以及对应的 LLM 工具注册（cron_* / workflow_*）在 [ENABLED] == false 时整体关闭。
 *
 * 设计取舍：
 *  - 用常量而非 BuildConfig / 用户设置项：退役是单向的，不给运行时重新打开的隐患。
 *  - 入口代码只是 `if (ENABLED)` 包住，不删除：工具实现、引擎逻辑、页面、路由、strings
 *    全部原样保留，最后由下个版本统一清理。
 *  - 旧数据库表（scheduled_job / scheduled_job_run / workflow 系列）与实体保留只读，
 *    不迁移不删除，避免老用户历史记录丢失；也不再有任何写入者。
 *  - 已排队的 WorkManager 任务（CronJobWorker）会静默结束并返回 success，不再产生 run 记录。
 */
object LegacyAutomation {
    const val ENABLED: Boolean = false
}