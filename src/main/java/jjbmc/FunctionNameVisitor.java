package jjbmc;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.TypeSolverBuilder;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import static jjbmc.ErrorLogger.warn;

@Getter
public class FunctionNameVisitor {
    private final List<String> unwinds = new ArrayList<>();
    private final List<String> functionNames = new ArrayList<>();
    private final boolean getAll;
    private final HashMap<String, List<String>> paramMap = new HashMap<>();
    private final List<TestBehaviour> functionBehaviours = new LinkedList<>();

    public FunctionNameVisitor(CompilationUnit cu, boolean getAll) {
        this.getAll = getAll;
        if (cu.getPrimaryType().isPresent()) {
            cu.getPrimaryType().get().getMembers().stream()
                    .filter(BodyDeclaration::isMethodDeclaration)
                    .forEach(it -> this.visit(it.asMethodDeclaration()));
        }
    }

    public static FunctionNameVisitor parseFile(Path fileName, boolean getAll) {
        try {
            var config = new ParserConfiguration();
            config.setProcessJml(true);
            TypeSolver typeSolver = new TypeSolverBuilder().withCurrentJRE().build();
            config.setSymbolResolver(new JavaSymbolSolver(typeSolver));
            var cu = new JavaParser(config).parse(fileName);
            if (!cu.isSuccessful()) {
                cu.getProblems().forEach(System.out::println);
                throw new RuntimeException();
            }
            return new FunctionNameVisitor(cu.getResult().get(), getAll);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public enum TestBehaviour {
        Verifyable,
        Fails,
        Ignored
    }

    public void visit(MethodDeclaration that) {
        var rm = that.resolve();
        //not interested in methods of inner classes
        if (that.getName().toString().contains("$")) {
            return;
        }
        String f = rm.getQualifiedName();
        String rtString = typeToString(that.getType());
        String paramString =
                that.getDeclarationAsString(false, false, false);
        if (f.endsWith("Verf") || f.endsWith("<init>") || getAll) {
            functionNames.add(f + ":" + paramString + rtString);
        }
        for (var p : that.getParameters()) {
            String name = f;
            if (that.hasModifier(Modifier.DefaultKeyword.STATIC)) {
                name = "$static_" + f;
            }
            paramMap.computeIfAbsent(name, it -> new LinkedList<>())
                    .add(p.getNameAsString());
        }
        translateAnnotations(that.getAnnotations());
    }

    private void translateAnnotations(NodeList<AnnotationExpr> annotations) {
        for (var annotation : annotations) {
            //var ra = annotation.resolve();
            switch (annotation.getNameAsString()) {
                case "Fails" -> functionBehaviours.add(TestBehaviour.Fails);
                case "Verifyable" -> functionBehaviours.add(TestBehaviour.Verifyable);
                case "Unwind" -> {
                    try {
                        unwinds.add(annotation.asSingleMemberAnnotationExpr()
                                .getMemberValue().asIntegerLiteralExpr()
                                .getValue());
                    } catch (Exception e) {
                        try {
                            unwinds.add(annotation.asNormalAnnotationExpr()
                                    .getPairs().getFirst().orElse(null)
                                    .getValue().asIntegerLiteralExpr()
                                    .getValue());
                        } catch (Exception e1) {
                            warn("Cannot parse annotation %s",annotation);
                        }
                    }
                }
                default -> warn("Found unknown annotation: %s", annotation);
            }
        }

        if (functionNames.size() != functionBehaviours.size()) {
            functionBehaviours.add(TestBehaviour.Ignored);
        }
        if (functionBehaviours.size() != unwinds.size()) {
            unwinds.add(null);
        }
    }

    private String typeToString(Type type) {
        return type.resolve().toDescriptor();
        /*if (type instanceof JCTree.JCPrimitiveTypeTree) {
            if (type.toString().equals("void")) {
                return "V";
            }
            if (type.toString().equals("int")) {
                return "I";
            }
            if (type.toString().equals("float")) {
                return "F";
            }
            if (type.toString().equals("double")) {
                return "D";
            }
            if (type.toString().equals("char")) {
                return "C";
            }
            if (type.toString().equals("long")) {
                return "J";
            }
            if (type.toString().equals("boolean")) {
                return "Z";
            }
            if (type.toString().equals("byte")) {
                return "B";
            }
            if (type.toString().equals("short")) {
                return "S";
            }
            throw new UnsupportedException("Unkown type " + type + ". Cannot call JBMC.");
        } else if (type instanceof JCTree.JCArrayTypeTree) {
            return "[" + typeToString(((JCTree.JCArrayTypeTree) type).elemtype);
        } else if (type != null) {
            if (type instanceof JCTree.JCIdent) {
                return "L" + ((JCTree.JCIdent) type).sym.flatName().toString().replace(".", "/") + ";";
            } else if (type instanceof JCTree.JCFieldAccess) {
                return "L" + ((JCTree.JCFieldAccess) type).sym.toString().replace(".", "/") + ";";
            } else {
                throw new UnsupportedException("Unkown type " + type + ". Cannot call JBMC.");
            }
        }
        return "V";*/
    }
}