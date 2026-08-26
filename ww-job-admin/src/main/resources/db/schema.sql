CREATE TABLE IF NOT EXISTS job_group (
                                         id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                         app_name VARCHAR(64) NOT NULL COMMENT '执行器标识',
    title VARCHAR(64) NOT NULL COMMENT '名称',
    address_type TINYINT DEFAULT 0 COMMENT '0自动注册 1手动',
    address_list VARCHAR(512) COMMENT '手动地址列表，逗号分隔',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_app_name (app_name)
    ) COMMENT '执行器分组';

CREATE TABLE IF NOT EXISTS job_info (
                                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                        job_group_id BIGINT NOT NULL COMMENT '执行器分组id',
                                        job_name VARCHAR(64) NOT NULL COMMENT '任务名',
    job_desc VARCHAR(255) COMMENT '描述',
    handler_name VARCHAR(64) NOT NULL COMMENT 'JobHandler 名',
    executor_param VARCHAR(512) COMMENT '任务参数',
    cron VARCHAR(64) NOT NULL COMMENT 'cron 表达式',
    route_strategy VARCHAR(32) DEFAULT 'round_robin',
    block_strategy VARCHAR(32) DEFAULT 'serial',
    retry_count INT DEFAULT 0,
    timeout INT DEFAULT 0 COMMENT '超时秒数，0不限制',
    alarm_config VARCHAR(512) COMMENT '报警配置',
    trigger_status TINYINT DEFAULT 1 COMMENT '1启用 0暂停',
    trigger_next_time BIGINT DEFAULT 0 COMMENT '下次触发毫秒时间戳',
    trigger_last_time BIGINT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_group (job_group_id),
    KEY idx_next_time (trigger_next_time)
    ) COMMENT '任务';

CREATE TABLE IF NOT EXISTS job_registry (
                                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                            job_group_id BIGINT NOT NULL,
                                            registry_key VARCHAR(64) NOT NULL COMMENT 'appName',
    registry_value VARCHAR(128) NOT NULL COMMENT 'ip:port',
    heartbeat_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_key_time (registry_key, heartbeat_time)
    ) COMMENT '执行器注册表';

CREATE TABLE IF NOT EXISTS job_log (
                                       id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                       job_id BIGINT NOT NULL,
                                       job_group_id BIGINT NOT NULL,
                                       executor_address VARCHAR(128) COMMENT '执行地址',
    handler_name VARCHAR(64),
    trigger_type VARCHAR(16) COMMENT 'cron/manual/retry',
    trigger_time DATETIME COMMENT '触发时间',
    handle_time DATETIME COMMENT '执行完成时间',
    handle_code INT COMMENT '200成功 500失败',
    handle_msg VARCHAR(1024) COMMENT '失败信息',
    status TINYINT DEFAULT 0 COMMENT '0运行中 1成功 2失败 3未知(超时/被阻塞，结果不确定)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_job (job_id),
    KEY idx_create (create_time)
    ) COMMENT '执行日志';