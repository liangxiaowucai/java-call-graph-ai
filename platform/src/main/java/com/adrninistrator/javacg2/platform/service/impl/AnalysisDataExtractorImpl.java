package com.adrninistrator.javacg2.platform.service.impl;

import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;
import com.adrninistrator.javacg2.platform.repository.RepositoryRepo;
import com.adrninistrator.javacg2.platform.service.AnalysisDataExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class AnalysisDataExtractorImpl implements AnalysisDataExtractor {
    
    private static final Logger logger = LoggerFactory.getLogger(AnalysisDataExtractorImpl.class);
    
    private final RepositoryRepo repositoryRepo;
    
    public AnalysisDataExtractorImpl(RepositoryRepo repositoryRepo) {
        this.repositoryRepo = repositoryRepo;
    }
    
    @Override
    public List<EnumConstant> extractEnumConstants(Long repoId, String fullMethod) {
        return extractEnumConstantsFromChain(repoId, List.of(fullMethod));
    }
    
    @Override
    public List<EnumConstant> extractEnumConstantsFromChain(Long repoId, List<String> methods) {
        Path outputDir = getAnalysisOutputDir(repoId);
        if (outputDir == null) return List.of();

        Set<String> methodSet = new HashSet<>(methods);

        // 1. 读取枚举定义：enum_init_assign_info.txt
        //    格式: enumClass:constructor | constName | ordinal | argSeq | valueType | arrayDim | value
        //    同一枚举常量有多行（每个构造参数一行），需要按 (enumClass, constName) 合并：
        //    - Integer/int 类型参数 → code 值
        //    - String 类型且长度>=2 → description
        // key: "enumClass|constName"  value: [code, description]
        Map<String, String[]> enumRaw = new LinkedHashMap<>(); // value: [ordinal, code, description]

        for (String line : readTsvFile(outputDir, "enum_init_assign_info")) {
            String[] cols = line.split("\t");
            if (cols.length < 7) continue;

            String enumMethod = cols[0];
            String constName = cols[1];
            String ordinal = cols[2];
            String valueType = cols[4];
            String value = cols[6];

            String enumClass = enumMethod.contains(":")
                ? enumMethod.substring(0, enumMethod.lastIndexOf(':')) : enumMethod;
            String key = enumClass + "|" + constName;

            String[] entry = enumRaw.computeIfAbsent(key, k -> new String[]{ordinal, "", ""});
            // entry[0]=ordinal, entry[1]=code, entry[2]=description
            if (value != null && !value.isBlank()) {
                if ("java.lang.Integer".equals(valueType) || "int".equals(valueType)
                        || "java.lang.Long".equals(valueType) || "long".equals(valueType)) {
                    if (entry[1].isEmpty()) entry[1] = value; // 取第一个整型参数作为 code
                } else if (valueType != null && valueType.contains("String") && value.length() >= 2) {
                    if (entry[2].isEmpty()) entry[2] = value; // 取第一个字符串参数作为 description
                }
            }
        }

        // 2. 读取方法中使用的静态字段（枚举常量）：method_call_static_field.txt
        Map<String, EnumConstant> enumMap = new LinkedHashMap<>();
        for (String line : readTsvFile(outputDir, "method_call_static_field")) {
            String[] cols = line.split("\t");
            if (cols.length < 7) continue;

            String fieldClass = cols[3];
            String fieldName = cols[4];
            String callerMethod = cols[6];

            if (!methodSet.contains(callerMethod)) continue;

            String key = fieldClass + "|" + fieldName;
            String[] raw = enumRaw.get(key);
            if (raw != null) {
                String code = raw[1].isEmpty() ? raw[0] : raw[1]; // 有整型 code 用 code，否则用 ordinal
                String description = raw[2];
                if (!enumMap.containsKey(key)) {
                    enumMap.put(key, new EnumConstant(fieldClass, fieldName, code, description));
                }
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
        Path outputDir = getAnalysisOutputDir(repoId);
        if (outputDir == null) return List.of();
        
        Set<String> methodSet = new HashSet<>(methods);
        List<MethodConstantUsage> constants = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        
        // 1. 方法返回的常量值：method_return_const_value.txt
        //    格式: fullMethod | returnType | valueSeq | valueType | arrayDim | value
        for (String line : readTsvFile(outputDir, "method_return_const_value")) {
            String[] cols = line.split("\t");
            if (cols.length < 6) continue;
            
            String fullMethodName = cols[0];
            String valueType = cols[3];
            String value = cols[5];
            
            if (!methodSet.contains(fullMethodName)) continue;
            if (value == null || value.isBlank() || value.equals("null")) continue;
            
            String key = fullMethodName + "|RETURN_CONST|" + value;
            if (seen.add(key)) {
                constants.add(new MethodConstantUsage(fullMethodName, "返回常量", value, "RETURN_CONST", 0));
            }
        }
        
        // 2. 方法中使用的静态字段（常量）：method_call_static_field.txt
        //    格式: callId | ? | ? | fieldClass | fieldName | fieldType | callerMethod | returnType | lineNum
        for (String line : readTsvFile(outputDir, "method_call_static_field")) {
            String[] cols = line.split("\t");
            if (cols.length < 9) continue;
            
            String fieldClass = cols[3];
            String fieldName = cols[4];
            String callerMethod = cols[6];
            String lineNumStr = cols[8];
            
            if (!methodSet.contains(callerMethod)) continue;
            
            // 只取全大写的常量名（约定）
            if (fieldName != null && fieldName.equals(fieldName.toUpperCase()) && fieldName.length() >= 2) {
                int lineNum = 0;
                try {
                    lineNum = Integer.parseInt(lineNumStr);
                } catch (NumberFormatException ignored) {}
                
                String shortClass = fieldClass.contains(".") ? fieldClass.substring(fieldClass.lastIndexOf('.') + 1) : fieldClass;
                String key = callerMethod + "|STATIC_FIELD|" + shortClass + "." + fieldName;
                if (seen.add(key)) {
                    constants.add(new MethodConstantUsage(callerMethod, shortClass + "." + fieldName, "", "STATIC_FIELD", lineNum));
                }
            }
        }
        
        // 3. 方法中使用的枚举字段
        //    通过 enum_init_assign_info 和 method_call_static_field 的组合已经在上面处理了
        
        return constants;
    }
    
    @Override
    public Map<String, List<EnumConstant>> extractAllEnums(Long repoId) {
        Path outputDir = getAnalysisOutputDir(repoId);
        if (outputDir == null) return Map.of();

        // key: "enumClass|constName"  value: [ordinal, code, description]
        Map<String, String[]> enumRaw = new LinkedHashMap<>();

        for (String line : readTsvFile(outputDir, "enum_init_assign_info")) {
            String[] cols = line.split("\t");
            if (cols.length < 7) continue;

            String enumMethod = cols[0];
            String constName = cols[1];
            String ordinal = cols[2];
            String valueType = cols[4];
            String value = cols[6];

            String enumClass = enumMethod.contains(":")
                ? enumMethod.substring(0, enumMethod.lastIndexOf(':')) : enumMethod;
            String key = enumClass + "|" + constName;

            String[] entry = enumRaw.computeIfAbsent(key, k -> new String[]{ordinal, "", ""});
            if (value != null && !value.isBlank()) {
                if ("java.lang.Integer".equals(valueType) || "int".equals(valueType)
                        || "java.lang.Long".equals(valueType) || "long".equals(valueType)) {
                    if (entry[1].isEmpty()) entry[1] = value;
                } else if (valueType != null && valueType.contains("String") && value.length() >= 2) {
                    if (entry[2].isEmpty()) entry[2] = value;
                }
            }
        }

        Map<String, List<EnumConstant>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : enumRaw.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            String enumClass = parts[0];
            String constName = parts[1];
            String[] raw = e.getValue();
            String code = raw[1].isEmpty() ? raw[0] : raw[1];
            String description = raw[2];
            result.computeIfAbsent(enumClass, k -> new ArrayList<>())
                  .add(new EnumConstant(enumClass, constName, code, description));
        }

        return result;
    }
    
    @Override
    public List<FieldConstant> extractAllFieldConstants(Long repoId) {
        Path outputDir = getAnalysisOutputDir(repoId);
        if (outputDir == null) return List.of();
        
        List<FieldConstant> constants = new ArrayList<>();
        
        // 读取字段信息：field_info.txt
        //    格式: className | fieldName | fieldType | modifiers | ... | staticFlag | finalFlag | ...
        for (String line : readTsvFile(outputDir, "field_info")) {
            String[] cols = line.split("\t");
            if (cols.length < 10) continue;
            
            String className = cols[0];
            String fieldName = cols[1];
            String fieldType = cols[2];
            String modifiers = cols[4]; // 包含 private/public/static/final
            
            // 只提取 static final 字段（常量）
            if (modifiers.contains("static") && modifiers.contains("final")) {
                // 值需要从其他地方获取，这里暂时留空
                constants.add(new FieldConstant(className, fieldName, fieldType, ""));
            }
        }
        
        return constants;
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 获取分析结果输出目录
     */
    private Path getAnalysisOutputDir(Long repoId) {
        var repoOpt = repositoryRepo.findById(repoId);
        if (repoOpt.isEmpty()) {
            logger.warn("Repository not found: {}", repoId);
            return null;
        }
        
        RepositoryEntity repo = repoOpt.get();
        
        // 构建输出目录路径：data/analysis/repo_{id}/_collected_jars-javacg2_merged.jar-output_javacg2
        // 使用绝对路径或当前工作目录
        Path baseDir = Paths.get("data/analysis/repo_" + repoId);
        File dir = baseDir.toFile();
        
        if (!dir.exists()) {
            // 尝试使用 platform/ 前缀（如果从项目根目录运行）
            baseDir = Paths.get("platform/data/analysis/repo_" + repoId);
            dir = baseDir.toFile();
        }
        
        if (!dir.exists()) {
            logger.warn("Analysis directory not found: {} (working dir: {})", 
                baseDir.toAbsolutePath(), System.getProperty("user.dir"));
            return null;
        }
        
        // 查找输出目录（可能有不同的命名）
        File[] subDirs = dir.listFiles(File::isDirectory);
        if (subDirs == null || subDirs.length == 0) {
            logger.warn("No output directory found in: {}", baseDir.toAbsolutePath());
            return null;
        }
        
        // 返回第一个以 -output_javacg2 结尾的目录
        for (File subDir : subDirs) {
            if (subDir.getName().endsWith("-output_javacg2")) {
                logger.debug("Found analysis output directory: {}", subDir.getAbsolutePath());
                return subDir.toPath();
            }
        }
        
        logger.warn("No javacg2 output directory found in: {}", baseDir.toAbsolutePath());
        return null;
    }
    
    /**
     * 读取 TSV 文件的所有行
     */
    private List<String> readTsvFile(Path outputDir, String fileName) {
        List<String> lines = new ArrayList<>();
        File file = outputDir.resolve(fileName + ".txt").toFile();
        
        if (!file.exists()) {
            logger.debug("File not found: {}", file.getAbsolutePath());
            return lines;
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to read file: {}", file.getAbsolutePath(), e);
        }
        
        return lines;
    }
}
