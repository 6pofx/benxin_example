-- ============================================================================
-- exp-10 · 智能订单助手 —— 建表脚本
-- ----------------------------------------------------------------------------
-- 这份脚本必须同时能在 H2（MODE=MySQL，DATABASE_TO_LOWER=TRUE）与本机 MySQL 8 上执行，
-- 因此刻意只用两者都认的最朴素语法：
--   * 主键：BIGINT AUTO_INCREMENT（H2 的 MySQL 兼容模式认这个写法）
--   * 时间：TIMESTAMP
--   * 文本：VARCHAR / TEXT
--   * 幂等：一律 CREATE TABLE IF NOT EXISTS，重复执行不报错、不丢数据
-- 禁止出现 ENGINE= / CHARSET= / ON UPDATE CURRENT_TIMESTAMP / JSON 类型这类
-- "只有一边认"的方言特性 —— 那会让"同一套 schema 跑两种库"变成一句空话。
--
-- 关于二级索引（实测结论，值得记一笔）：这里刻意「不」写 CREATE INDEX。
-- H2 认 `CREATE INDEX IF NOT EXISTS`，而 MySQL 8 至今不支持这个子句
-- （实测报 ERROR 1064）；反过来在 CREATE TABLE 里内联 `INDEX idx (col)` 也不是
-- H2 的通用语法。示例数据只有几行，二级索引对查询没有任何影响，
-- 而"同一份脚本必须能在两种库上重复执行"是硬要求 —— 于是选择不写二级索引。
-- 唯一性约束（UNIQUE）两边都认，因此照常保留：它是"事务一致性"演示的靶子。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 客户
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customer (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    level       VARCHAR(16)  NOT NULL,
    phone       VARCHAR(32),
    created_at  TIMESTAMP    NOT NULL
);

-- ---------------------------------------------------------------------------
-- 订单明细
--   sku 上的唯一约束是"事务一致性"演示的靶子：插入重复 sku 会抛异常，
--   演示里用它证明"订单 + 审计要么都成、要么都不成"。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS order_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT       NOT NULL,
    sku         VARCHAR(64)  NOT NULL,
    amount      DECIMAL(12, 2) NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uk_order_item_sku UNIQUE (sku)
);

-- ---------------------------------------------------------------------------
-- 运行审计：一次 Agent 运行一行（由自定义 AgentListener 落库）
--   status 只有 OK / FAILED 两态：正常结束时由 onAgentEnd 写 OK，
--   抛异常时由 onError 补写 FAILED —— 后者是监听器的补偿动作，不是框架行为。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_run (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_name        VARCHAR(64)  NOT NULL,
    session_id        VARCHAR(128) NOT NULL,
    loop_name         VARCHAR(64),
    model_name        VARCHAR(64),
    steps             INT          NOT NULL,
    input_tokens      INT          NOT NULL,
    output_tokens     INT          NOT NULL,
    duration_ms       BIGINT       NOT NULL,
    finish_reason     VARCHAR(32),
    max_steps_reached INT          NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    error_message     TEXT,
    output_summary    TEXT,
    created_at        TIMESTAMP    NOT NULL
);

-- ---------------------------------------------------------------------------
-- 工具调用审计：一次工具调用一行（由自定义 AgentInterceptor 落库）
--   short_circuit = 1 表示这次调用被 beforeTool 短路了：工具没有真的执行，
--   但审计仍然留痕 —— 这正是拦截器审计比"在工具方法里埋点"更强的地方。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tool_audit (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool_use_id   VARCHAR(96),
    tool_name     VARCHAR(64)  NOT NULL,
    agent_name    VARCHAR(64),
    session_id    VARCHAR(128),
    step          INT          NOT NULL,
    arguments     TEXT,
    error         INT          NOT NULL,
    short_circuit INT          NOT NULL,
    duration_ms   BIGINT       NOT NULL,
    result_digest TEXT,
    created_at    TIMESTAMP    NOT NULL
);

-- ---------------------------------------------------------------------------
-- 会话记忆：一条 ChatMessage 一行（由自定义 JDBC MemoryStore 落库）
--   seq 是消息在会话内的顺序号，load() 按它排序还原消息顺序。
--   主键用 (session_id, seq) 复合键：同一会话内顺序唯一，不同会话互不干扰。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_memory (
    session_id VARCHAR(128) NOT NULL,
    seq        INT          NOT NULL,
    role       VARCHAR(16)  NOT NULL,
    payload    TEXT         NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (session_id, seq)
);

-- ---------------------------------------------------------------------------
-- 待审批的写操作：审批被拒（或需要人工兜底）时落一行
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS approval_request (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool_name   VARCHAR(64)  NOT NULL,
    agent_name  VARCHAR(64),
    session_id  VARCHAR(128),
    arguments   TEXT,
    reason      TEXT,
    decided_by  VARCHAR(64),
    approved    INT          NOT NULL,
    status      VARCHAR(24)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL
);
