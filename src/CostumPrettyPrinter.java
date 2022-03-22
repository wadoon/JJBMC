import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;
import org.jmlspecs.openjml.JmlPretty;
import org.jmlspecs.openjml.JmlTree;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashSet;
import java.util.Set;

public class CostumPrettyPrinter extends JmlPretty {
    int currentLine = 1;
    private final TraceInformation ti = new TraceInformation();
    private Set<String> assertVars = new HashSet<>();

    public CostumPrettyPrinter(Writer out, boolean sourceOutput) {
        super(out, sourceOutput);
    }

    @Override
    public void println() throws IOException {
        currentLine += 1;
        super.println();
    }

    @Override
    public void visitAnnotation(JCTree.JCAnnotation tree) {
        //super.visitAnnotation(tree);
    }

    //@Override
    //public void printStat(JCTree tree) throws IOException {
    //    lineMap.put(currentLine, TranslationUtils.getLineNumber(tree));
    //    super.printStat(tree);
    //}


    @Override
    public void visitNewClass(JCTree.JCNewClass tree) {
        super.visitNewClass(tree);
        ti.addMethodCall(currentLine);
    }

    @Override
    public void visitApply(JCTree.JCMethodInvocation tree) {
        super.visitApply(tree);
        ti.addMethodCall(currentLine);
    }

    @Override
    public void visitAssign(JCTree.JCAssign tree) {
        super.visitAssign(tree);
        String name = tree.getVariable().toString();
        if(name.startsWith("this.")) {
            name = name.substring(5);
        }
        ti.addAssignment(currentLine, name);
    }

    @Override
    public void visitJmlMethodDecl(JmlTree.JmlMethodDecl that) {
        ti.addMethod(currentLine + 1, that.getName().toString(), that.mods.getFlags().contains(Modifier.STATIC));
        ti.addLineEquality(currentLine + 1, TranslationUtils.getLineNumber(that));
        for(JCTree.JCVariableDecl p : that.params){
            ti.addParam(p.getName().toString(), that.getName().toString());
        }
        super.visitJmlMethodDecl(that);
    }

    @Override
    public void visitIdent(JCTree.JCIdent tree) {
        super.visitIdent(tree);
        if(!(tree.sym instanceof Symbol.MethodSymbol) && !tree.toString().equals("this")) {
            assertVars.add(tree.toString());
        }
    }

    @Override
    public void visitAssert(JCTree.JCAssert tree) {
        assertVars = new HashSet<>();
        super.visitAssert(tree);
        ti.addAssertVars(currentLine, assertVars);
        ti.addAssert(currentLine, tree.toString());
        assertVars = new HashSet<>();
    }

    @Override
    public void visitSelect(JCTree.JCFieldAccess tree) {
        super.visitSelect(tree);
        String name = tree.toString();
        if(name.startsWith("this.")) {
            name = name.substring(5);
        }
        assertVars.add(name);
    }

    @Override
    public void visitVarDef(JCTree.JCVariableDecl that) {
        super.visitVarDef(that);
        if(that.sym.owner instanceof Symbol.MethodSymbol && !that.sym.owner.name.toString().equals("<init>")) {
            ti.addLocalVar(that.getName().toString(), that.sym.owner.name.toString());
        }
        if(that.init != null) {
            ti.addAssignment(currentLine, that.getName().toString());
        }
        ti.addLineEquality(currentLine, TranslationUtils.getLineNumber(that));
    }

    @Override
    public void visitClassDef(JCTree.JCClassDecl tree) {
        ti.addLineEquality(currentLine +1, TranslationUtils.getLineNumber(tree));
        super.visitClassDef(tree);
    }

    @Override
    public void visitBlock(JCTree.JCBlock tree) {
        try {
            this.print("{");
            this.println();
            this.indent();
            for(JCTree.JCStatement st : tree.getStatements()) {
                this.align();
                this.printStat(st);
                if(!(st instanceof JCTree.JCBlock)) {
                    ti.addLineEquality(currentLine, TranslationUtils.getLineNumber(st));
                }
                this.println();
            }
            this.println();
            this.undent();
            this.align();
            this.print("}");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static PrettyPrintInformation prettyPrint(JCTree tree) {
        try {
            StringWriter sw = new StringWriter();
            CostumPrettyPrinter cpp = new CostumPrettyPrinter(sw, true);
            tree.accept(cpp);
            //for(int key : cpp.lineMap.keySet()) {
            //    System.out.println(key + " : " + cpp.lineMap.get(key));
            //}
            return new PrettyPrintInformation(sw.toString(), cpp.ti);
        } catch (Exception var3) {
            throw new TranslationException("Error pretty printing translated AST.");
        }
    }
}
