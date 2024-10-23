package pascal.taie.analysis.pta.plugin;

import pascal.taie.World;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.solver.DeclaredParamProvider;
import pascal.taie.analysis.pta.core.solver.EntryPoint;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.ir.exp.InvokeInstanceExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Subsignature;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.NullType;
import pascal.taie.language.type.Type;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class UnserializeEntryPointHandler implements Plugin {
    private Solver solver;
    private String findclass = "java.util.HashMap";

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
    }

    @Override
    public void onStart() {
        //add HashMap readObject to EntryPoint
        List<JClass> list = solver.getHierarchy().allClasses().toList();
        List<Type> paramType = new ArrayList<>();
        for(JClass jClass : list){
            if(jClass.getName().equals(findclass)){
                System.out.println("find class");
//                paramType.add(NullType.NULL);
                //Subsignature subsignature = Subsignature.get("readObject", paramType, new ClassType(jClass.getClassLoader(), "java.lang.Object"));
                JMethod jMethod = jClass.getDeclaredMethod("readObject");
                if(jMethod != null){
                    System.out.println("entry add");
                    solver.addEntryPoint(new EntryPoint(jMethod, new DeclaredParamProvider(jMethod, solver.getHeapModel())));
                }
            }
        }
    }

    @Override
    public void onPhaseFinish() {
        solver.getCallGraph().reachableMethods().forEach(csMethod -> {
            if (csMethod.getMethod().getDeclaringClass().getName().equals("java.net.URL")){
                csMethod.getMethod().getIR().getStmts().forEach(stmt1 -> {
                    if(stmt1 instanceof Invoke invoke && (invoke.isVirtual() || invoke.isInterface()) && invoke.getRValue() instanceof InvokeInstanceExp invokeInstanceExp){
                        Var var = invokeInstanceExp.getBase();
                        Context context = csMethod.getContext();
                        if (solver.getCSManager().getCSVar(context, var).getPointsToSet() == null || solver.getCSManager().getCSVar(context, var).getPointsToSet().isEmpty()){
                            JClass jclass = World.get().getClassHierarchy().getClass(var.getType().getName());
                            Collection<JClass> implementors = new ArrayList<>();
                            if(invoke.isInterface()){
                                implementors.addAll(World.get().getClassHierarchy().getDirectImplementorsOf(jclass));
                            }else {
                                implementors.add(jclass);
                                implementors.addAll(World.get().getClassHierarchy().getDirectSubclassesOf(jclass));
                            }
                            System.out.printf("%s %s %s %s\n", csMethod.getMethod().getName(), var, jclass, implementors);
                            implementors.forEach(implementor ->{
                                solver.addPointsTo(solver.getCSManager().getCSVar(csMethod.getContext(), var), csMethod.getContext(), solver.getHeapModel().getMockObj(()->"Unserialzie", implementor.getName(), implementor.getType()));
                            });
                        }
                    }
                });
            }
        });
    }
}
