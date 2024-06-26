package jjbmc.utils;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.TypeSolverBuilder;
import com.google.common.collect.ImmutableList;
import jjbmc.FunctionNameVisitor.TestBehaviour;
import jjbmc.JJBMCOptions;
import jjbmc.Operations;
import org.junit.jupiter.api.Assumptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static jjbmc.ErrorLogger.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Created by jklamroth on 12/3/18.
 */
public class Utils {
    public static final Path SRC_TEST_JAVA = Paths.get("src", "test", "java");

    public static final Path TMP_FOLDER = Paths.get("tmp");
    public static final Path SRC_TEST_RESOURCES = SRC_TEST_JAVA.getParent().resolve("resources");

    private static final boolean filterOutput = false;

    public static Stream<JJBMCTest> prepareParameters(Path fileName) throws Exception {
        JJBMCOptions options = new JJBMCOptions();
        options.keepTranslation = true;
        options.setDebugMode(true);
        options.setFileName(fileName);
        options.setTmpFolder(TMP_FOLDER.resolve(
                fileName.getFileName().toString().replace(".java", "")));

        createAnnotationsFolder(options.getTmpFolder());

        Operations operations = new Operations(options);
        operations.prepareSource();
        operations.compile();

        debug("Parsing file for functions.");
        ParserConfiguration config = new ParserConfiguration();
        config.setJmlKeys(ImmutableList.of(ImmutableList.of("openjml")));
        config.setProcessJml(true);
        config.setSymbolResolver(new JavaSymbolSolver(
                new TypeSolverBuilder()
                        .withSourceCode(options.getTmpFolder())
                        .withCurrentJRE()
                        .build()));
        JavaParser parser = new JavaParser(config);

        ParseResult<CompilationUnit> result;
        try {
            result = parser.parse(fileName);
        } catch (IOException e) {
            System.out.println("Error parsing file: " + fileName);
            throw new RuntimeException(e);
        }

        if (!result.isSuccessful()) {
            System.out.println(fileName);
            result.getProblems().forEach(System.out::println);
            return Stream.of();
        }

        List<TestOptions> testOptions = new ArrayList<>(32);
        result.getResult().get().accept(new TestOptionsListener(), testOptions);
        var params = testOptions.stream()
                .filter(it -> it.behaviour() != TestBehaviour.Ignored)
                .map(it -> new JJBMCTest(operations, it));
        debug("Found %s functions", testOptions.size());
        return params;
    }


    private static void createAnnotationsFolder(Path path) throws IOException {
        var dir = path.resolve("jjbmc");
        info("Copying Annotation files to %s", dir.toAbsolutePath());

        Files.createDirectories(dir);

        Files.copy(SRC_TEST_JAVA.resolve("jjbmc/Fails.java"),
                dir.resolve("Fails.java"),
                StandardCopyOption.REPLACE_EXISTING);

        Files.copy(SRC_TEST_JAVA.resolve("jjbmc/Verifyable.java"),
                dir.resolve("Verifyable.java"),
                StandardCopyOption.REPLACE_EXISTING);

        Files.copy(SRC_TEST_JAVA.resolve("jjbmc/Unwind.java"),
                dir.resolve("Unwind.java"),
                StandardCopyOption.REPLACE_EXISTING);
    }

    public static void runTests(JJBMCTest test) throws InterruptedException, IOException {
        var topts = test.topts();
        var opts = test.op().getOptions();

        var function = topts.functionName();
        var classFile = Objects.requireNonNull(opts.getTmpFile());
        var unwind = topts.unwinds();

        if (topts.behaviour() == TestBehaviour.Ignored) {
            warn("Function: %s ignored due to missing annotation.", function);
            Assumptions.abort();
        }

        info("Running test for function: %s", function);

        List<String> commandList = new ArrayList<>();
        if (opts.isWindows()) {
            if (function.contains("()")) {
                function = function.replace("<init>", "<clinit>");
            }
            function = "\"%s\"".formatted(function);
            //classFile = classFile.replaceAll("\\\\", "/");
            commandList.add("cmd.exe");
            commandList.add("/c");
        }

        commandList.add(opts.getJbmcBinary().toString());
        commandList.add(classFile.toAbsolutePath().toString());
        commandList.add("--function");
        commandList.add(function);

        if (unwind != -1) {
            commandList.add("--unwind");
            commandList.add(String.valueOf(unwind));
        }

        info("Run jbmc with commands: %s", commandList);

        var parentDir = opts.getTmpFolder();

        Process proc = new ProcessBuilder(commandList)
                .directory(parentDir.toFile())
                .start();


        BufferedReader stdInput = new BufferedReader(new
                InputStreamReader(proc.getInputStream()));

        BufferedReader stdError = new BufferedReader(new
                InputStreamReader(proc.getErrorStream()));
        proc.waitFor();

        var out = stdInput.lines().toList();
        var errors = stdError.lines().toList();

        System.out.format("JBMC Output for file: %s with function %s%n", classFile, function);
        out.stream()
                .filter(s -> !filterOutput || s.contains("**") || s.contains("FAILURE") || s.contains("VERIFICATION"))
                .forEach(System.out::println);
        errors.forEach(System.out::println);

        /*
                if (!filterOutput) {
            info(out);
            info(errOut);
        }*/

        TestBehaviour behaviour = topts.behaviour();
        assertFalse(out.contains("FAILURE") && behaviour == TestBehaviour.Verifyable);
        assertFalse(out.contains("SUCCESSFUL") && behaviour == TestBehaviour.Fails);
        assertTrue(out.contains("VERIFICATION"));
    }
}
