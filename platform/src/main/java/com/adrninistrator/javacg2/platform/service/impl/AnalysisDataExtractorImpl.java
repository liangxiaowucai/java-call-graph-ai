package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.EnumConstantEntity;
import com.adrninistrator.javacg2.platform.entity.FieldConstantEntity;
import com.adrninistrator.javacg2.platform.entity.MethodReturnConstEntity;
import com.adrninistrator.javacg2.platform.entity.StaticFieldUsageEntity;
import com.adrninistrator.javacg2.platform.repository.EnumConstantRepo;
import com.adrninistrator.javacg2.platform.repository.FieldConstantRepo;
import com.adrninistrator.javacg2.platform.repository.MethodReturnConstRepo;
import com.adrninistrator.javacg2.platform.repository.StaticFieldUsageRepo;
import com.adrninistrator.javacg2.platform.service.AnalysisDataExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 从数据库固化表中提取结构化数据（枚举/常量/字段），用于文档生成、源码面板等。
 * 数据在分析阶段由 BytecodeAnalyzerImpl 从 javacg2 中间文件解析入库，
 * 本类查询时只读库、不再依赖 data/analysis 中间文件。
 */
@Service
public class AnalysisDataExtractorImpl implements AnalysisDataExtractor {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisDataExtractorImpl.class);

    private final EnumConstantRepo enumConstantRepo;
    private final StaticFieldUsageRepo staticFieldUsageRepo;
    private final MethodReturnConstRepo methodReturnConstRepo;
    private final FieldConstantRepo fieldConstantRepo;

    public AnalysisDataExtractorImpl(EnumConstantRepo enumConstantRepo,
                                     StaticFieldUsageRepo staticFieldUsageRepo,
                                     MethodReturnConstRepo methodReturnConstRepo,
                                     FieldConstantRepo fieldConstantRepo) {
        this.enumConstantRepo = enumConstantRepo;
        this.staticFieldUsageRepo = staticFieldUsageRepo;
        this.methodReturnConstRepo = methodReturnConstRepo;
        this.fieldConstantRepo = fieldConstantRepo;
    }

    /** 枚举 code：有整型 code 用 code，否则回退到 ordinal。 */
    private String enumCode(EnumConstantEntity e) {
        return (e.getCode() != null && !e.getCode().isEmpty()) ? e.getCode() : e.getOrdinal();
    }

    @Override
    public List<EnumConstant> extractEnumConstants(Long repoId, String fullMethod) {
        return extractEnumConstantsFromChain(repoId, List.of(fullMethod));
    }

    @Override
    public List<EnumConstant> extractEnumConstantsFromChain(Long repoId, List<String> methods) {
        Set<String> methodSet = new HashSet<>(methods);

        // 枚举定义：key = enumClass|constName
        Map<String, EnumConstantEntity> enumByKey = new HashMap<>();
        for (EnumConstantEntity e : enumConstantRepo.findByRepoId(repoId)) {
            enumByKey.put(e.getEnumClass() + "|" + e.getConstName(), e);
        }

        // 方法用到的静态字段 → 匹配枚举定义
        Map<String, EnumConstant> enumMap = new LinkedHashMap<>();
        for (StaticFieldUsageEntity u : staticFieldUsageRepo.findByRepoId(repoId)) {
            if (!methodSet.contains(u.getCallerMethod())) continue;
            String key = u.getFieldClass() + "|" + u.getFieldName();
            EnumConstantEntity def = enumByKey.get(key);
            if (def != null && !enumMap.containsKey(key)) {
                enumMap.put(key, new EnumConstant(u.getFieldClass(), u.getFieldName(),
                        enumCode(def), def.getDescription()));
            }
        }
        return new ArrayList<>(enumMap.values());
    }

    @Override
    public List<MethodConstantUsage> extractMethodConstants(Long repoId, String fullMethod) {
        return extractMethodConstantsFromChain(repoId, List.of(fullMethod));
    }

    @Override
    public List<MethodConstantUsage> extractMethodConstantsFromChain(Long repoId, List<String> methods) {
        Set<String> methodSet = new HashSet<>(methods);
        List<MethodConstantUsage> constants = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. 方法返回的常量值
        for (MethodReturnConstEntity r : methodReturnConstRepo.findByRepoId(repoId)) {
            if (!methodSet.contains(r.getFullMethod())) continue;
            String value = r.getValue();
            if (value == null || value.isBlank() || value.equals("null")) continue;
            String key = r.getFullMethod() + "|RETURN_CONST|" + value;
            if (seen.add(key)) {
                constants.add(new MethodConstantUsage(r.getFullMethod(), "返回常量", value, "RETURN_CONST", 0));
            }
        }

        // 2. 方法中使用的静态字段（只取全大写常量名）
        for (StaticFieldUsageEntity u : staticFieldUsageRepo.findByRepoId(repoId)) {
            if (!methodSet.contains(u.getCallerMethod())) continue;
            String fieldName = u.getFieldName();
            if (fieldName == null || !fieldName.equals(fieldName.toUpperCase()) || fieldName.length() < 2) continue;
            String fieldClass = u.getFieldClass();
            String shortClass = fieldClass != null && fieldClass.contains(".")
                    ? fieldClass.substring(fieldClass.lastIndexOf('.') + 1) : fieldClass;
            int lineNum = u.getLineNum() != null ? u.getLineNum() : 0;
            String key = u.getCallerMethod() + "|STATIC_FIELD|" + shortClass + "." + fieldName;
            if (seen.add(key)) {
                constants.add(new MethodConstantUsage(u.getCallerMethod(),
                        shortClass + "." + fieldName, "", "STATIC_FIELD", lineNum));
            }
        }
        return constants;
    }

    @Override
    public Map<String, List<EnumConstant>> extractAllEnums(Long repoId) {
        Map<String, List<EnumConstant>> result = new LinkedHashMap<>();
        for (EnumConstantEntity e : enumConstantRepo.findByRepoId(repoId)) {
            result.computeIfAbsent(e.getEnumClass(), k -> new ArrayList<>())
                  .add(new EnumConstant(e.getEnumClass(), e.getConstName(), enumCode(e), e.getDescription()));
        }
        return result;
    }

    @Override
    public List<FieldConstant> extractAllFieldConstants(Long repoId) {
        List<FieldConstant> constants = new ArrayList<>();
        for (FieldConstantEntity f : fieldConstantRepo.findByRepoId(repoId)) {
            constants.add(new FieldConstant(f.getClassName(), f.getFieldName(),
                    f.getFieldType(), f.getValue() != null ? f.getValue() : ""));
        }
        return constants;
    }
}
