import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.ReturnTree;
import com.sun.tools.javac.code.JmlTypes;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.comp.JmlAttr;
import com.sun.tools.javac.jvm.ClassReader;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Position;
import org.jmlspecs.openjml.JmlSpecs;
import org.jmlspecs.openjml.JmlTokenKind;
import org.jmlspecs.openjml.JmlTreeCopier;
import org.jmlspecs.openjml.JmlTreeUtils;
import org.jmlspecs.openjml.Nowarns;
import org.jmlspecs.openjml.Strings;
import org.jmlspecs.openjml.Utils;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.sun.tools.javac.tree.JCTree.*;
import static org.jmlspecs.openjml.JmlTree.*;
import static com.sun.tools.javac.util.List.*;

/**
 * Created by jklamroth on 11/13/18.
 */
public class VerifyFunctionVisitor extends JmlTreeCopier {
    private final Maker M;
    private final Context context;
    private final Log log;
    private final Names names;
    private final Nowarns nowarns;
    private final Symtab syms;
    private final Types types;
    private final Utils utils;
    private final JmlTypes jmltypes;
    private final JmlSpecs specs;
    private final JmlTreeUtils treeutils;
    private final JmlAttr attr;
    private final Name resultName;
    private final Name exceptionName;
    private final Name exceptionNameCall;
    private final Name terminationName;
    private final ClassReader reader;
    private final Symbol.ClassSymbol utilsClass;
    private final JCIdent preLabel;
    private Set<JCExpression> ensuresList = new HashSet<>();
    private Set<JCExpression> requiresList = new HashSet<>();
    private JmlMethodDecl currentMethod;
    private int boolVarCounter = 0;
    private List<JCStatement> newStatements = List.nil();
    private List<JCStatement> combinedNewReqStatements = List.nil();
    private List<JCStatement> combinedNewEnsStatements = List.nil();
    private Symbol returnBool = null;
    private Symbol returnVar = null;
    private boolean hasReturn = false;
    private List<Object> visited = List.nil();
    private JmlToAssertVisitor.TranslationMode translationMode = JmlToAssertVisitor.TranslationMode.JAVA;
    private Map<Integer, JCVariableDecl> oldVars = new HashMap<>();
    private  final BaseVisitor baseVisitor;

    public enum TranslationMode { REQUIRES, ENSURES, JAVA}

    public VerifyFunctionVisitor(Context context, Maker maker, BaseVisitor base) {
        super(context, maker);
        baseVisitor = base;
        this.context = context;
        this.log = Log.instance(context);
        this.M = Maker.instance(context);
        this.names = Names.instance(context);
        this.nowarns = Nowarns.instance(context);
        this.syms = Symtab.instance(context);
        this.types = Types.instance(context);
        this.utils = Utils.instance(context);
        this.specs = JmlSpecs.instance(context);
        this.jmltypes = JmlTypes.instance(context);
        this.treeutils = JmlTreeUtils.instance(context);
        this.attr = JmlAttr.instance(context);
        this.resultName = names.fromString(Strings.resultVarString);
        this.exceptionName = names.fromString(Strings.exceptionVarString);
        this.exceptionNameCall = names.fromString(Strings.exceptionCallVarString);
        this.terminationName = names.fromString(Strings.terminationVarString);
        this.reader = ClassReader.instance(context);
        this.reader.init(syms);
        this.utilsClass = reader.enterClass(names.fromString(Strings.runtimeUtilsFQName));
        this.preLabel = treeutils.makeIdent(Position.NOPOS, Strings.empty, syms.intType);
    }

    @Override
    public JCTree visitJmlMethodClauseExpr(JmlMethodClauseExpr that, Void p) {
        returnBool = null;
        if(that.token == JmlTokenKind.ENSURES) {
            translationMode = JmlToAssertVisitor.TranslationMode.ENSURES;
        } else if(that.token == JmlTokenKind.REQUIRES) {
            translationMode = JmlToAssertVisitor.TranslationMode.REQUIRES;
        }
        JmlMethodClauseExpr copy = (JmlMethodClauseExpr)super.visitJmlMethodClauseExpr(that, p);
        if(translationMode == JmlToAssertVisitor.TranslationMode.ENSURES) {
            if(returnBool != null) {
                newStatements = newStatements.append(M.at(currentMethod.body.pos).Assert(copy.expression, null));
            }
            combinedNewEnsStatements = combinedNewEnsStatements.appendList(newStatements);
        } else if(translationMode == JmlToAssertVisitor.TranslationMode.REQUIRES){
            if(returnBool != null) {
                newStatements = newStatements.append(translationUtils.makeAssumeStatement(super.copy(copy.expression), M));
            }
            combinedNewReqStatements = combinedNewReqStatements.appendList(newStatements);
        }
        newStatements = List.nil();
        translationMode = JmlToAssertVisitor.TranslationMode.JAVA;
        return copy;
    }

    @Override
    public JCTree visitJmlSingleton(JmlSingleton that, Void p) {
        switch (that.token) {
            case BSRESULT:
                return M.Ident(returnVar);
            default:
                throw new RuntimeException("JmlSingleton with type " + that.token + " is not supported.");
        }
    }

    @Override
    public JCTree visitJmlQuantifiedExpr(JmlQuantifiedExpr that, Void p) {
        if(!visited.contains(that)) {
            JmlQuantifiedExpr copy = that;
            visited = visited.append(that);

            if(translationMode == JmlToAssertVisitor.TranslationMode.ENSURES) {
                if(copy.op == JmlTokenKind.BSFORALL) {
                    JCIdent classCProver = M.Ident(M.Name("CProver"));
                    JCFieldAccess nondetFunc = M.Select(classCProver, M.Name("nondetInt"));
                    List<JCExpression> largs = List.nil();
                    JCVariableDecl quantVar = treeutils.makeVarDef(syms.intType, names.fromString(that.decls.get(0).getName().toString()), currentMethod.sym, M.Apply(List.nil(), nondetFunc, largs));
                    newStatements = newStatements.append(quantVar);
                    newStatements = newStatements.append(translationUtils.makeAssumeStatement(super.copy(copy.range), M));
                    JCExpression value = super.copy(copy.value);
                    return value;
                } else if(copy.op == JmlTokenKind.BSEXISTS) {
                    JCExpression value = super.copy(copy.value);
                    JCVariableDecl boolVar = treeutils.makeVarDef(syms.booleanType, names.fromString("b_" + boolVarCounter++), currentMethod.sym, treeutils.makeLit(Position.NOPOS, syms.booleanType, false));
                    JCBinary b = M.Binary(Tag.OR, M.Ident(boolVar), value);
                    newStatements = newStatements.append(M.Exec(M.Assign(M.Ident(boolVar), b)));
                    List<JCStatement> l = List.nil();
                    l.append(boolVar);
                    l.append(makeStandardLoop(copy.range, newStatements, that.decls.get(0).getName().toString()));
                    newStatements = l;
                    returnBool = boolVar.sym;
                    return M.Ident(boolVar);
                }
            } else if(translationMode == JmlToAssertVisitor.TranslationMode.REQUIRES) {
                if(copy.op == JmlTokenKind.BSFORALL) {
                    JCExpression value = super.copy(copy.value);
                    JCVariableDecl boolVar = treeutils.makeVarDef(syms.booleanType, names.fromString("b_" + boolVarCounter++), currentMethod.sym, treeutils.makeLit(Position.NOPOS, syms.booleanType, false));
                    JCBinary b = M.Binary(Tag.AND, M.Ident(boolVar), value);
                    newStatements = newStatements.append(M.Exec(M.Assign(M.Ident(boolVar), b)));
                    List<JCStatement> l = List.nil();
                    l.append(boolVar);
                    l.append(makeStandardLoop(copy.range, newStatements, that.decls.get(0).getName().toString()));
                    newStatements = l;
                    returnBool = boolVar.sym;
                    return M.Ident(boolVar);
                } else if(copy.op == JmlTokenKind.BSEXISTS) {
                    JCIdent classCProver = M.Ident(M.Name("CProver"));
                    JCFieldAccess nondetFunc = M.Select(classCProver, M.Name("nondetInt"));
                    List<JCExpression> largs = List.nil();
                    JCVariableDecl quantVar = treeutils.makeVarDef(syms.intType, names.fromString(that.decls.get(0).getName().toString()), currentMethod.sym, M.Apply(List.nil(), nondetFunc, largs));
                    newStatements = newStatements.append(quantVar);
                    newStatements = newStatements.append(translationUtils.makeAssumeStatement(super.copy(copy.range), M));
                    JCExpression value = super.copy(copy.value);
                    return value;
                }
            }

            return copy;
        }
        return that;
    }

    @Override
    public JCTree visitBinary(BinaryTree node, Void p) {
        if(!visited.contains(node) && translationMode != JmlToAssertVisitor.TranslationMode.JAVA) {
            visited = visited.append(node);
            JCBinary copy = (JCBinary) super.visitBinary(node, p);
            if(copy.operator.asType().getReturnType() == syms.booleanType) {
                JCVariableDecl boolVar = treeutils.makeVarDef(syms.booleanType, names.fromString("b_" + boolVarCounter++), currentMethod.sym, copy);
                //newStatements.append(boolVar);
                returnBool = boolVar.sym;
                return copy;
            }
        }
        return super.visitBinary(node, p);
    }

    @Override
    public JCTree visitLiteral(LiteralTree node, Void p) {
        if(!visited.contains(node) && translationMode != JmlToAssertVisitor.TranslationMode.JAVA) {
            visited = visited.append(node);
            JCLiteral copy = (JCLiteral) super.visitLiteral(node, p);
            if(copy.type.baseType() == syms.booleanType) {
                JCVariableDecl boolVar = treeutils.makeVarDef(syms.booleanType, names.fromString("b_" + boolVarCounter++), currentMethod.sym, copy);
                newStatements = newStatements.append(boolVar);
                returnBool = boolVar.sym;
                return M.Ident(boolVar);
            }
        }
        return super.visitLiteral(node, p);
    }

    @Override
    public JCTree visitReturn(ReturnTree node, Void p) {
        hasReturn = true;
        JCReturn copy = (JCReturn)super.visitReturn(node, p);
        JCAssign assign = null;
        if(returnVar != null) {
            assign = M.Assign(M.Ident(returnVar), copy.getExpression());
        }
        JCExpression ty = M.at(copy).Type(baseVisitor.getExceptionClass().type);
        JCThrow throwStmt = M.Throw(M.NewClass(null, null, ty, List.nil(), null));
        if(assign != null) {
            JCBlock block = M.Block(0L, List.of(M.Exec(assign), throwStmt));
            return block;
        } else {
            JCBlock block = M.Block(0L, List.of(throwStmt));
            return block;
        }
    }

    @Override
    public JCTree visitJmlMethodDecl(JmlMethodDecl that, Void p) {
        requiresList.clear();
        ensuresList.clear();
        currentMethod = that;
        hasReturn = false;
        JCVariableDecl returnVar = null;
        Type t = that.sym.getReturnType();
        if(!(t instanceof Type.JCVoidType)) {
            returnVar = treeutils.makeVarDef(t, M.Name("returnVar"), currentMethod.sym, treeutils.makeNullLiteral(Position.NOPOS));
            this.returnVar = returnVar.sym;
        } else {
            this.returnVar = null;
        }
        JmlMethodDecl copy = (JmlMethodDecl)super.visitJmlMethodDecl(that, p);
        JCVariableDecl catchVar = treeutils.makeVarDef(syms.exceptionType, M.Name("e"), currentMethod.sym, Position.NOPOS);
        JCExpression ty = M.at(that).Type(syms.runtimeExceptionType);
        JCExpression msg = treeutils.makeStringLiteral(that.pos, "Specification is not well defined for method " + that.getName());
        JCThrow throwStmt = M.Throw(M.NewClass(null, null, ty, List.of(msg), null));
        JCTry reqTry = M.Try(M.Block(0L, List.from(combinedNewReqStatements)),
                List.of(M.Catch(catchVar, M.Block(0L, List.of(throwStmt)))), null);
        JCTry ensTry = M.Try(M.Block(0L, List.from(combinedNewEnsStatements)),
                List.of(M.Catch(catchVar, M.Block(0L, List.of(throwStmt)))), null);

        JCVariableDecl catchVarb = treeutils.makeVarDef(baseVisitor.getExceptionClass().type, M.Name("ex"), currentMethod.sym, Position.NOPOS);

        List< JCStatement> l = List.nil();
        for(JCVariableDecl variableDecl : oldVars.values()) {
            copy.body.stats = copy.body.getStatements().prepend(variableDecl);
        }

        if(hasReturn) {
            if(returnVar != null) {
                List< JCStatement> l1 = List.nil();
                JCReturn returnStmt = M.Return(M.Ident(returnVar));
                if(combinedNewEnsStatements.size() > 0) {
                    l1 = l1.append(ensTry);
                }
                l1 = l1.append(returnStmt);
                JCTry bodyTry = M.Try(M.Block(0L, copy.body.getStatements()),
                        List.of(
                                M.Catch(catchVarb, M.Block(0L, l1))
                        ),
                        null);
                if(combinedNewReqStatements.size() > 0) {
                    l = l.append(reqTry);
                }
                l = l.append(returnVar).append(bodyTry);
            } else {
                if(combinedNewEnsStatements.size() > 0) {
                    JCTry bodyTry = M.Try(M.Block(0L, copy.body.getStatements()),
                            List.of(
                                    M.Catch(catchVarb, M.Block(0L, List.of(ensTry)))
                            ),
                            null);
                    l = l.append(reqTry).append(bodyTry);
                } else {
                    l = copy.body.getStatements().prepend(reqTry);
                }
            }
        } else {
            l = copy.body.getStatements();
            if(combinedNewEnsStatements.size() > 0) {
                l = l.append(ensTry);
            }
            if(combinedNewReqStatements.size() > 0) {
                l = l.prepend(reqTry);
            }
        }
        currentMethod.body = M.Block(0L, l);

        currentMethod.methodSpecsCombined = null;
        currentMethod.cases = null;
        combinedNewReqStatements = List.nil();
        combinedNewEnsStatements = List.nil();
        return currentMethod;
    }

    @Override
    public JCTree visitJmlMethodInvocation(JmlMethodInvocation that, Void p) {
        switch (that.token) {
            case BSOLD:
                JCExpression arg = that.getArguments().get(0);
                JCVariableDecl oldVar;
                if(arg.type instanceof Type.JCPrimitiveType) {
                    oldVar = treeutils.makeVarDef(arg.type, M.Name(String.valueOf("old" + arg.hashCode())), currentMethod.sym, arg);
                } else {
                    oldVar = treeutils.makeVarDef(arg.type, M.Name(String.valueOf("old" + arg.hashCode())), currentMethod.sym,
                            M.Apply(List.nil(),
                                    M.Select(arg, M.Name("clone")),
                                    List.nil()));
                }
                oldVars.put(arg.hashCode(), oldVar);
                return M.Ident(oldVar);
            default:
                throw new RuntimeException("JmlMethodInvocation with type " + that.token + " is not supported.");
        }
    }

    private JCStatement makeStandardLoop(JCExpression range, List<JCStatement> body, String loopVarName) {
        JCVariableDecl loopVar = treeutils.makeVarDef(syms.intType, names.fromString(loopVarName), currentMethod.sym, treeutils.makeLit(Position.NOPOS, syms.intType, 0));
        JCExpression loopCond = range;
        JCExpressionStatement loopStep = M.Exec(treeutils.makeUnary(Position.NOPOS, Tag.PREINC, treeutils.makeIdent(Position.NOPOS, loopVar.sym)));
        List<JCExpressionStatement> loopStepl = List.from(Collections.singletonList(loopStep));
        JCBlock loopBodyBlock = M.Block(0L, List.from(body));
        List<JCStatement> loopVarl = List.from(Collections.singletonList(loopVar));
        return M.ForLoop(loopVarl, loopCond, loopStepl, loopBodyBlock);
    }
}
