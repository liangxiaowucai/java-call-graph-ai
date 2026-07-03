package com.adrninistrator.javacg2.platform.service;

import com.adrninistrator.javacg2.platform.entity.SystemConfigEntity;
import com.adrninistrator.javacg2.platform.repository.SystemConfigRepo;
import com.adrninistrator.javacg2.platform.service.impl.QAEngineImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Prompt 通用注册表。
 * <p>所有对 AI 使用的「系统 prompt / 分析框架」集中在此登记：每条含 key、展示名、说明与内置默认值。
 * 消费方统一调用 {@link #get(String)} 取值（数据库有覆盖用覆盖，否则用内置默认），
 * 从而避免 prompt 散落在各处硬编码，且可在系统配置界面统一编辑。
 * <p>新增 prompt 只需在此 {@code DEFS} 注册，配置界面会自动出现对应可编辑项。
 */
@Service
public class PromptService {

    /** 单条 prompt 定义 */
    public record PromptDef(String key, String configKey, String label,
                            String description, String defaultText) {}

    // ── 内置默认 prompt 文本 ────────────────────────────────────────────────

    /** QA 问答 - 工具调用规则（function calling 铁律/策略/收尾铁律） */
    static final String QA_TOOL_RULES = ""
            + "## 工具调用铁律\n"
            + "1. 每个结论必须引用工具返回的具体源码，格式：`类名.方法名`\n"
            + "2. 工具返回 SOURCE_NOT_FOUND 时，只能说\"源码中未找到 XXX 的实现\"\n"
            + "3. 工具返回 NO_CALLEES/NO_CALLERS 时，如实说明\n"
            + "4. 禁止对未读取源码的方法做任何推断\n"
            + "5. 如果分析到安全阀上限仍未找到根因，列出\"已分析方法\"和\"缺少的信息\"\n"
            + "\n## 工具调用策略（必须遵循）\n"
            + "1. **第一步必须是 getChainOutline**：对每个入口方法先调 getChainOutline 拿到整条调用链地图，再根据用户问题意图决定深入哪些节点，不要对所有节点无差别展开\n"
            + "2. **目标驱动深挖**：地图上标有 [DB/HTTP/CACHE/MQ] 的节点与问题相关时，才用 getBoundaries/getConstants/getMethodSource 读取明细；与问题无关的节点跳过\n"
            + "3. **并行读取**：确定需要深入多个方法时，在同一轮同时调用多个工具（如同时调 getMethodSource A + getMethodSource B + getBoundaries C），不要串行逐个调用；每轮尽量把同类需求合并\n"
            + "4. **按需读源码**：getMethodSource 只在需要理解具体业务逻辑时调用，不要对每个方法都调用\n"
            + "5. **多态分派**：遇到接口/抽象方法时，先调 getImplementations 找到实现类，再读实现类源码\n"
            + "6. **入参按需分析**：只有问题涉及入参字段、校验规则时，才调 getParamClassDef\n"
            + "7. **忽略样板代码**：跳过 getter/setter、日志打印、toString 等无业务含义的代码\n"
            + "8. **外部调用必须完整列出**：getBoundaries/getConstants/getChainOutline 返回的「外部调用」条目（已装配 系统名+完整URL+用途）必须在答案里逐条列出，按 `**系统名**：HTTP调用 \\`完整URL\\` 用途` 的格式，一条都不能漏，URL 必须是 base+path 拼好的完整地址\n"
            + "9. **结构化输出**：用表格展示字段、用代码块展示关键逻辑、用列表展示调用链\n"
            + "\n## 收尾铁律\n"
            + "答案末尾必须追加一节 `**分析覆盖**`，分两行列出：\n"
            + "- `已分析`：本次实际读取了源码的关键方法（`类名.方法名`）\n"
            + "- `未覆盖/不确定`：想分析但源码未读取到或无法确定的部分；没有则写「无」\n";

    /** 请求链分析 - 分析框架（作为「问题」前缀，后接请求链摘要） */
    static final String DEBUG_ANALYSIS = ""
            + "请以资深后端性能与架构专家的视角，排查下面这条真实请求链路的**性能瓶颈、根因和风险点**，"
            + "并给出可落地的优化方案（定位到具体类名/方法名/URL，给出代码或配置示例）。\n"
            + "请用工具按需读取相关方法源码、外部调用边界、常量与异常，不要泛泛而谈。\n"
            + "输出结构：## 瓶颈定位 / ## 根因分析 / ## 具体优化方案 / ## 风险点。\n";

    /** 文档生成 - 系统人设 */
    static final String DOC_SYSTEM = ""
            + "你是一个源码分析专家，正在将代码逻辑翻译成所有人都能看懂的文档。\n"
            + "你的读者可能是产品经理、测试工程师、客服人员或新入职的研发。\n\n"
            + "## 核心原则\n"
            + "1. 用业务语言描述，不要贴代码片段\n"
            + "2. 每个结论必须标注来源：(见 `类名.方法名`)\n"
            + "3. 校验规则要具体到值：不要说\"有校验\"，要说\"商品名称必填，最多50个字符\"\n"
            + "4. 异常场景要说清触发条件和用户感知\n"
            + "5. 严禁编造任何内容：所有技术组件、外部依赖、字段、逻辑、状态，必须在提供的源码或数据中有明确依据才能写出，不得根据常识或经验推断补全\n"
            + "6. 如果提供的信息不足以确定某项内容，直接省略该项，不要猜测或假设\n"
            + "7. 使用 Mermaid 图表（sequenceDiagram/flowchart/stateDiagram），图表中的节点只能来自已提供的信息\n"
            + "8. 只输出 Markdown，不要解释性文字\n";

    /** 发布文档 - 版本摘要 */
    static final String RELEASE_SUMMARY = "你是技术文档助手，生成简洁专业的版本摘要。";

    /** 发布文档 - 评审建议 */
    static final String RELEASE_REVIEW = "你是资深架构师，列出代码评审关注点，每条以「- 」开头，不超过5条。";

    // ── 注册表 ──────────────────────────────────────────────────────────────

    private final List<PromptDef> defs = List.of(
            new PromptDef("qa.system", "claude.system.prompt",
                    "QA 问答 · 基础人设",
                    "智能问答的核心系统 prompt，约束回答只基于真实源码，定义各类问题的回答策略。",
                    QAEngineImpl.DEFAULT_SYSTEM_PROMPT),
            new PromptDef("qa.tool_rules", "prompt.qa.tool_rules",
                    "QA 问答 · 工具调用规则",
                    "function calling 循环的工具调用铁律、策略与收尾要求，追加在基础人设之后。",
                    QA_TOOL_RULES),
            new PromptDef("debug.analysis", "prompt.debug.analysis",
                    "请求链分析 · 分析框架",
                    "调用链追踪 AI 分析的任务框架（性能瓶颈/根因/优化/风险），作为问题前缀。",
                    DEBUG_ANALYSIS),
            new PromptDef("doc.system", "prompt.doc.system",
                    "文档生成 · 系统人设",
                    "自动生成项目文档时的系统 prompt，约束用业务语言、标注来源、不编造。",
                    DOC_SYSTEM),
            new PromptDef("product.doc", "prompt.product.doc",
                    "产品文档 · 系统人设",
                    "生成产品需求文档（面向产品/测试/业务方）时的系统 prompt，业务语言、含判断值与错误码。",
                    com.adrninistrator.javacg2.platform.service.impl.DocGenerator.PRODUCT_DOC_SYSTEM_PROMPT_WITHOUT_DIAGRAM),
            new PromptDef("release.summary", "prompt.release.summary",
                    "发布文档 · 版本摘要",
                    "发布文档中生成版本变更摘要时的系统 prompt。",
                    RELEASE_SUMMARY),
            new PromptDef("release.review", "prompt.release.review",
                    "发布文档 · 评审建议",
                    "发布文档中生成代码评审关注点时的系统 prompt。",
                    RELEASE_REVIEW)
    );

    private final SystemConfigRepo configRepo;

    public PromptService(SystemConfigRepo configRepo) {
        this.configRepo = configRepo;
    }

    /** 取指定 prompt：数据库有非空覆盖用覆盖，否则用内置默认。key 不存在时抛异常（属编程错误）。 */
    public String get(String key) {
        PromptDef def = find(key);
        return configRepo.findByConfigKey(def.configKey())
                .map(SystemConfigEntity::getConfigValue)
                .filter(s -> s != null && !s.isBlank())
                .orElse(def.defaultText());
    }

    /** 列出所有 prompt 定义与当前值（含默认值与用户覆盖值），供配置界面渲染。 */
    public List<Map<String, Object>> listWithValues() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PromptDef def : defs) {
            String override = configRepo.findByConfigKey(def.configKey())
                    .map(SystemConfigEntity::getConfigValue)
                    .filter(s -> s != null && !s.isBlank())
                    .orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", def.key());
            m.put("label", def.label());
            m.put("description", def.description());
            m.put("defaultText", def.defaultText());
            m.put("value", override);              // null 表示使用默认
            m.put("customized", override != null); // 是否已自定义
            list.add(m);
        }
        return list;
    }

    /**
     * 保存 prompt 覆盖值。value 为 null 或空白 → 删除覆盖（恢复默认）。
     * @return 是否为已知 key
     */
    public boolean saveOverride(String key, String value) {
        PromptDef def = findOrNull(key);
        if (def == null) return false;
        if (value == null || value.isBlank()) {
            configRepo.findByConfigKey(def.configKey()).ifPresent(configRepo::delete);
            return true;
        }
        SystemConfigEntity entity = configRepo.findByConfigKey(def.configKey())
                .orElseGet(() -> {
                    SystemConfigEntity e = new SystemConfigEntity();
                    e.setConfigKey(def.configKey());
                    return e;
                });
        entity.setConfigValue(value);
        entity.setUpdatedAt(LocalDateTime.now());
        configRepo.save(entity);
        return true;
    }

    private PromptDef find(String key) {
        PromptDef def = findOrNull(key);
        if (def == null) throw new IllegalArgumentException("未知 prompt key: " + key);
        return def;
    }

    private PromptDef findOrNull(String key) {
        for (PromptDef d : defs) {
            if (d.key().equals(key)) return d;
        }
        return null;
    }
}
