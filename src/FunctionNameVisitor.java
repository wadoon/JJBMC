import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jmlspecs.openjml.Factory;
import org.jmlspecs.openjml.IAPI;
import org.jmlspecs.openjml.JmlTree;
import org.jmlspecs.openjml.JmlTreeScanner;

import java.util.ArrayList;
import java.util.List;

class FunctionNameVisitor extends JmlTreeScanner {
    private static Logger log = LogManager.getLogger(FunctionNameVisitor.class);
    static private List<String> functionNames = new ArrayList<>();
    static private List<TestBehaviour> functionBehaviours = new ArrayList<>();
    static private List<String> unwinds = new ArrayList<>();

    public enum TestBehaviour {
        Verifyable,
        Fails,
        Ignored
    }

    public static List<String> getFunctionNames() {
        return functionNames;
    }

    public static List<String> getUnwinds() {
        return unwinds;
    }

    public static List<TestBehaviour> getFunctionBehaviours() {
        return functionBehaviours;
    }

    @Override
    public void visitJmlMethodDecl(JmlTree.JmlMethodDecl that) {
        //not interested in methods of inner classes
        if(that.sym.owner.flatName().toString().contains("$")) {
            return;
        }
        String f = that.sym.owner.toString() + "." + that.getName().toString();
        String rtString = returnTypeString(that.restype);
        String paramString = getParamString(that.params);
        functionNames.add(f + ":" + paramString + rtString);
        translateAnnotations(that.mods.annotations);
        super.visitJmlMethodDecl(that);
    }

    private void translateAnnotations(List<JCTree.JCAnnotation> annotations) {
        for (JCTree.JCAnnotation annotation : annotations) {
            if (annotation.annotationType.toString().equals("Fails")) {
                functionBehaviours.add(TestBehaviour.Fails);
            } else if (annotation.annotationType.toString().equals("Verifyable")) {
                functionBehaviours.add(TestBehaviour.Verifyable);
            } else if (annotation.annotationType.toString().equals("Unwind")) {
                try {
                    unwinds.add(((JCTree.JCAssign) annotation.args.get(0)).rhs.toString());
                } catch (Exception e) {
                    log.warn("Cannot parse annotation " + annotation.toString());
                }
            } else {
                functionBehaviours.add(TestBehaviour.Ignored);
            }
        }
        if (functionNames.size() != functionBehaviours.size()) {
            functionBehaviours.add(TestBehaviour.Ignored);
        }
        if (functionBehaviours.size() != unwinds.size()) {
            unwinds.add(null);
        }
    }

    static void parseFile(String fileName) {
        functionNames = new ArrayList<>();
        functionBehaviours = new ArrayList<>();
        unwinds = new ArrayList<>();
        try {
            String[] args = {fileName};
            IAPI api = Factory.makeAPI();
            java.util.List<JmlTree.JmlCompilationUnit> cu = api.parseFiles(args);
            int a = api.typecheck(cu);
            //System.out.printf("a=%d", a);

            Context ctx = api.context();
            FunctionNameVisitor fnv = new FunctionNameVisitor();
            for (JmlTree.JmlCompilationUnit it : cu) {
                //log.info(api.prettyPrint(rewriteRAC(it, ctx)));
                ctx.dump();
                it.accept(fnv);
            }
        } catch (Exception e) {
            if(CLI.debugMode) {
                e.printStackTrace();
            }
            throw new RuntimeException("Error parsing for function names.");
        }
    }

    private String returnTypeString(JCTree.JCExpression rtType) {
        return typeToString(rtType);
    }

    private String getParamString(List<JCTree.JCVariableDecl> params) {
        String res = "(";
        for (JCTree.JCVariableDecl p : params) {
            res += typeToString(p.vartype);
        }
        return res + ")";
    }

    private String typeToString(JCTree.JCExpression type) {
        if (type instanceof JCTree.JCPrimitiveTypeTree) {
            if (type.toString().equals("void"))
                return "V";
            if (type.toString().equals("int"))
                return "I";
            if (type.toString().equals("float"))
                return "F";
            if (type.toString().equals("double"))
                return "D";
            if (type.toString().equals("char"))
                return "C";
            if (type.toString().equals("long"))
                return "J";
            if (type.toString().equals("boolean"))
                return "Z";
            if (type.toString().equals("byte"))
                return "B";
            if (type.toString().equals("short"))
                return "S";
            throw new RuntimeException("Unkown type " + type.toString() + ". Cannot call JBMC.");
        } else if (type instanceof JCTree.JCArrayTypeTree) {
            return "[" + typeToString(((JCTree.JCArrayTypeTree) type).elemtype);
        } else if (type != null) {
            if(type instanceof JCTree.JCIdent) {
                return "L" + ((JCTree.JCIdent) type).sym.toString().replace(".", "/") + ";";
            } else if (type instanceof JCTree.JCFieldAccess) {
                return "L" + ((JCTree.JCFieldAccess) type).sym.toString().replace(".", "/") + ";";
            } else {
                throw new RuntimeException("Unkown type " + type.toString() + ". Cannot call JBMC.");
            }
        }
        return "V";
    }
}
