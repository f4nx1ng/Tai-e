package pascal.taie.analysis.pta.plugin;

import pascal.taie.analysis.pta.core.solver.DeclaredParamProvider;
import pascal.taie.analysis.pta.core.solver.EntryPoint;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Subsignature;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.NullType;
import pascal.taie.language.type.Type;

import java.util.ArrayList;
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
}
