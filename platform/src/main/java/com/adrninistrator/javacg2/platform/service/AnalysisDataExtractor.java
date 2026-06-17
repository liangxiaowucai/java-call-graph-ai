package com.adrninistrator.javacg2.platform.service;

import java.util.List;
import java.util.Map;

/**
 * 从静态分析结果文件中提取结构化数据
 * 用于产品文档生成、代码分析等场景
 */
public interface AnalysisDataExtractor {
    
    /**
     * 枚举常量信息
     * @param enumClass 枚举类全名
     * @param constName 常量名（如 PAID, REFUNDED）
     * @param code 常量对应的code值
     * @param description 中文描述
     */
    record EnumConstant(String enumClass, String constName, String code, String description) {}
    
    /**
     * 字段常量信息
     * @param className 类全名
     * @param fieldName 字段名
     * @param fieldType 字段类型
     * @param value 常量值（如果是静态final字段）
     */
    record FieldConstant(String className, String fieldName, String fieldType, String value) {}
    
    /**
     * 方法中使用的常量信息
     * @param fullMethod 方法全名
     * @param constantName 常量名称
     * @param constantValue 常量值
     * @param usageType 使用类型（ENUM_FIELD, STATIC_FIELD, RETURN_CONST）
     * @param lineNumber 使用行号
     */
    record MethodConstantUsage(String fullMethod, String constantName, String constantValue, String usageType, int lineNumber) {}
    
    /**
     * 提取指定方法中使用的所有枚举常量
     * @param repoId 仓库ID
     * @param fullMethod 方法全名
     * @return 枚举常量列表
     */
    List<EnumConstant> extractEnumConstants(Long repoId, String fullMethod);
    
    /**
     * 提取调用链中所有方法使用的枚举常量
     * @param repoId 仓库ID
     * @param methods 方法全名列表
     * @return 枚举常量列表（去重）
     */
    List<EnumConstant> extractEnumConstantsFromChain(Long repoId, List<String> methods);
    
    /**
     * 提取指定方法中使用的所有常量
     * @param repoId 仓库ID
     * @param fullMethod 方法全名
     * @return 方法常量使用列表
     */
    List<MethodConstantUsage> extractMethodConstants(Long repoId, String fullMethod);
    
    /**
     * 提取调用链中所有方法使用的常量
     * @param repoId 仓库ID
     * @param methods 方法全名列表
     * @return 方法常量使用列表（去重）
     */
    List<MethodConstantUsage> extractMethodConstantsFromChain(Long repoId, List<String> methods);
    
    /**
     * 提取整个仓库的所有枚举定义
     * @param repoId 仓库ID
     * @return 枚举类名 -> 枚举常量列表
     */
    Map<String, List<EnumConstant>> extractAllEnums(Long repoId);
    
    /**
     * 提取整个仓库的所有静态常量字段
     * @param repoId 仓库ID
     * @return 字段常量列表
     */
    List<FieldConstant> extractAllFieldConstants(Long repoId);
}
