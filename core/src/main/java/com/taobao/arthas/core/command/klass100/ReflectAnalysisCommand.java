package com.taobao.arthas.core.command.klass100;

import static com.google.common.primitives.Ints.MAX_POWER_OF_TWO;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.taobao.arthas.core.command.model.EchoModel;
import com.taobao.arthas.core.command.model.ReflectAnalysisModel;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.shell.command.ExitStatus;
import com.taobao.arthas.core.util.CommandUtils;
import com.taobao.arthas.core.util.InstrumentationUtils;
import com.taobao.arthas.core.util.SearchUtils;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Summary;
import java.io.BufferedWriter;
import java.io.File;
import java.io.OutputStreamWriter;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.benf.cfr.reader.api.CfrDriver;

/**
 * @author: Ares
 * @time: 2023-12-19 17:33:14
 * @description: 反射分析
 * @version: JDK 1.8
 */
@Name("reflect-analysis")
@Summary("Analyze the reflection situation within the application")
public class ReflectAnalysisCommand extends AnnotatedCommand {

  private static final Logger LOGGER = LoggerFactory.getLogger(DumpClassCommand.class);

  private static final String TEMP_DIR = System.getProperty("java.io.tmpdir");

  @Override
  public void process(CommandProcess process) {
    String resultFilePath = "reflect-analysis-result.csv";
    String message = String.format(
        "The reflect analysis.result result is being generated asynchronously, check the %s file later",
        resultFilePath);
    CompletableFuture.runAsync(() -> processImpl(process, resultFilePath));
    process.appendResult(new EchoModel(message));
    ExitStatus status = ExitStatus.success();

    CommandUtils.end(process, status);
  }

  private ExitStatus processImpl(CommandProcess process, String resultFilePath) {
    ExitStatus status;
    try {
      Instrumentation inst = process.session().getInstrumentation();
      // 找到所有反射生成的sun.reflect.GeneratedMethodAccessor类
      Set<Class<?>> matchedClasses = SearchUtils.searchClass(inst,
          "sun.reflect.GeneratedMethodAccessor*", false);
      if (null != matchedClasses && !matchedClasses.isEmpty()) {
        ClassDumpTransformer transformer = new ClassDumpTransformer(matchedClasses);
        InstrumentationUtils.retransformClasses(inst, transformer, matchedClasses);
        Map<Class<?>, File> dumpResult = transformer.getDumpResult();

        Map<String, String> options = new HashMap<>(capacity(8));
        String outputDir = TEMP_DIR + "reflect-analysis";
        options.put("outputdir", outputDir);
        options.put("outputencoding", "UTF-8");
        options.put("encoding", "UTF-8");
        options.put("renamedupmembers", "true");
        options.put("hideutf", "false");

        options.put("renameillegalidents", "true");
        options.put("recover", "true");
        options.put("silent", "true");

        CfrDriver cfrDriver = new CfrDriver.Builder().withOptions(options).build();
        List<String> toAnalyse = new ArrayList<>();
        for (File value : dumpResult.values()) {
          toAnalyse.add(value.getAbsolutePath());
        }
        cfrDriver.analyse(toAnalyse);
        File file = new File(outputDir + File.separator + "sun" + File.separator + "reflect");
        if (file.exists() && file.isDirectory()) {
          File[] files = file.listFiles();
          if (null != files) {
            Map<String, List<String>> result = new HashMap<>(
                capacity(files.length));

            for (File javaFile : files) {
              CompilationUnit compilationUnit = StaticJavaParser.parse(javaFile);
              List<Node> childNodeList = compilationUnit.getChildNodes();
              String invokeClassName = null;
              for (Node compilationUnitChildNode : childNodeList) {
                if (compilationUnitChildNode instanceof ImportDeclaration) {
                  ImportDeclaration importDeclaration = (ImportDeclaration) compilationUnitChildNode;
                  String importClassName = importDeclaration.getNameAsString();
                  if (!importClassName.startsWith("java.") && !importClassName.startsWith("sun.")) {
                    invokeClassName = importClassName;
                    break;
                  }
                }
              }

              if (null != invokeClassName) {
                String refName = parseRefName(childNodeList);
                if (null != refName) {
                  String key = invokeClassName + "#" + refName;
                  if (!result.containsKey(key)) {
                    result.put(key, new ArrayList<>());
                  }
                  String generatedMethodAccessorName = javaFile.getName().replace(".java", "");
                  result.get(key).add(generatedMethodAccessorName);
                }
              }
            }

            processResult(process, result, resultFilePath);
          }
        }
      }
      status = ExitStatus.success();
    } catch (Throwable e) {
      LOGGER.error("processing error: ", e);
      process.end(-1, "processing error");
      status = ExitStatus.failure(-1, "reflect analysis fail");
    }
    return status;
  }

  private void processResult(CommandProcess process, Map<String, List<String>> result,
      String resultFilePath) {
    List<ReflectAnalysisModel> resultList = new ArrayList<>(result.size());
    result.forEach((key, valueList) -> {
      ReflectAnalysisModel reflectAnalysisModel = new ReflectAnalysisModel();
      reflectAnalysisModel.setRefName(key);
      reflectAnalysisModel.setGeneratedMethodAccessorNameCount(valueList.size());
      StringBuilder generatedMethodAccessorNames = new StringBuilder();
      for (String generatedMethodAccessorName : valueList) {
        generatedMethodAccessorNames.append(generatedMethodAccessorName).append(",");
      }
      reflectAnalysisModel.setGeneratedMethodAccessorNames(
          generatedMethodAccessorNames.substring(0,
              generatedMethodAccessorNames.length() - 1));
      resultList.add(reflectAnalysisModel);
    });
    resultList.sort((leftMode, rightModel) -> {
      int leftCount = leftMode.getGeneratedMethodAccessorNameCount();
      int rightCount = rightModel.getGeneratedMethodAccessorNameCount();
      if (leftCount > rightCount) {
        return -1;
      } else if (leftCount < rightCount) {
        return 1;
      } else {
        return leftMode.getRefName().compareTo(rightModel.getRefName());
      }
    });
    boolean async = true;
    if (async) {
      exportDataToCsvFile(resultList, resultFilePath);
    } else {
      for (ReflectAnalysisModel reflectAnalysisModel : resultList) {
        process.appendResult(reflectAnalysisModel);
      }
    }
  }

  private String parseRefName(List<Node> childNodeList) {
    for (Node compilationUnitChildNode : childNodeList) {
      if (compilationUnitChildNode instanceof ClassOrInterfaceDeclaration) {
        ClassOrInterfaceDeclaration classOrInterfaceDeclaration = (ClassOrInterfaceDeclaration) compilationUnitChildNode;
        for (Node classOrInterfaceDeclarationChildNode : classOrInterfaceDeclaration.getChildNodes()) {
          if (classOrInterfaceDeclarationChildNode instanceof MethodDeclaration) {
            MethodDeclaration methodDeclaration = (MethodDeclaration) classOrInterfaceDeclarationChildNode;
            if ("invoke".equals(methodDeclaration.getNameAsString())) {
              for (Node methodDeclarationChildNode : methodDeclaration.getChildNodes()) {
                if (methodDeclarationChildNode instanceof BlockStmt) {
                  BlockStmt blockStmt = (BlockStmt) methodDeclarationChildNode;
                  for (Node blockStmtChildNode : blockStmt.getChildNodes()) {
                    if (blockStmtChildNode instanceof TryStmt) {
                      TryStmt tryStmt = (TryStmt) blockStmtChildNode;
                      for (Node tryStmtChildNode : tryStmt.getChildNodes()) {
                        if (tryStmtChildNode instanceof BlockStmt) {
                          BlockStmt lineBlockStmt = (BlockStmt) tryStmtChildNode;
                          for (Node lineBlockStmtChildNode : lineBlockStmt.getChildNodes()) {
                            if (lineBlockStmtChildNode instanceof ReturnStmt) {
                              ReturnStmt returnStmt = (ReturnStmt) lineBlockStmtChildNode;
                              for (Node returnStmtChildNode : returnStmt.getChildNodes()) {
                                if (returnStmtChildNode instanceof MethodCallExpr) {
                                  MethodCallExpr methodCallExpr = (MethodCallExpr) returnStmtChildNode;
                                  for (Node methodCallExprChildNode : methodCallExpr.getChildNodes()) {
                                    if (methodCallExprChildNode instanceof SimpleName) {
                                      SimpleName simpleName = (SimpleName) methodCallExprChildNode;
                                      return simpleName.getIdentifier();
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
        break;
      }
    }
    return null;
  }


  private int capacity(int expectedSize) {
    if (expectedSize < 3) {
      return expectedSize + 1;
    }
    if (expectedSize < MAX_POWER_OF_TWO) {
      // This is the calculation used in JDK8 to resize when a putAll
      // happens; it seems to be the most conservative calculation we
      // can make.  0.75 is the default load factor.
      return (int) ((float) expectedSize / 0.75F + 1.0F);
    }
    return Integer.MAX_VALUE;
  }

  private void exportDataToCsvFile(List<ReflectAnalysisModel> reflectAnalysisModelList,
      String resultFilePath) {
    LOGGER.info("start write reflect analysis result: {} to file: {}",
        reflectAnalysisModelList.size(), resultFilePath);
    File file = new File(resultFilePath);
    try {
      if (file.exists()) {
        file.delete();
      }
      file.createNewFile();
      // write csv file
      try (BufferedWriter bufferedWriter = new BufferedWriter(
          new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
        bufferedWriter.write("count");
        bufferedWriter.write(",");
        bufferedWriter.write("refName");
        bufferedWriter.write(",");
        bufferedWriter.write("names");
        bufferedWriter.newLine();
        for (ReflectAnalysisModel reflectAnalysisModel : reflectAnalysisModelList) {
          bufferedWriter.write(
              String.valueOf(reflectAnalysisModel.getGeneratedMethodAccessorNameCount()));
          bufferedWriter.write(",");
          bufferedWriter.write(reflectAnalysisModel.getRefName());
          bufferedWriter.write(",");
          bufferedWriter.write(reflectAnalysisModel.getGeneratedMethodAccessorNames());
          bufferedWriter.newLine();
        }
        LOGGER.info("write csv file end");
      }
    } catch (Exception e) {
      LOGGER.error("export data to csv file exception: ", e);
    }
  }

}
