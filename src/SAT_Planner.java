package com.mycompany.sat_planner;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sat4j.core.Vec;
import org.sat4j.core.VecInt;
import org.sat4j.minisat.SolverFactory;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.IProblem;
import org.sat4j.specs.ISolver;
import org.sat4j.specs.IVecInt;
import org.sat4j.specs.TimeoutException;

import fr.uga.pddl4j.parser.DefaultParsedProblem;
import fr.uga.pddl4j.parser.Parser;
import fr.uga.pddl4j.parser.ParsedProblem;
import fr.uga.pddl4j.plan.Plan;
import fr.uga.pddl4j.plan.SequentialPlan;
import fr.uga.pddl4j.planners.AbstractPlanner;
import fr.uga.pddl4j.problem.DefaultProblem;
import fr.uga.pddl4j.problem.Fluent;
import fr.uga.pddl4j.problem.Problem;
import fr.uga.pddl4j.problem.operator.Action;
import fr.uga.pddl4j.util.BitVector;
import java.util.List;
import picocli.CommandLine;


@CommandLine.Command(
    name = "SAT",
    version = "SAT 1.4",
    description = "Solves a planning problem using a SAT solver.",
    sortOptions = false,
    mixinStandardHelpOptions = true,
    headerHeading = "Usage:%n",
    synopsisHeading = "%n",
    descriptionHeading = "%nDescription:%n%n",
    parameterListHeading = "%nParameters:%n",
    optionListHeading = "%nOptions:%n"
)

public class SAT_Planner extends AbstractPlanner {

    private static final Logger LOGGER = LogManager.getLogger(SAT_Planner.class.getName());
    private String outputFileName = null;
    private int planSize = 1;
    private long toEncodeTime = 0;
    private long toSearchTime = 0;

    @Override
    public boolean isSupported(Problem problem) {
        return problem instanceof DefaultProblem;
    }

    @Override
    public Problem instantiate(DefaultParsedProblem dpp) {
    LOGGER.info("Instantiating DefaultParsedProblem into DefaultProblem");
    try {
        DefaultProblem problem = new DefaultProblem(dpp);
        problem.instantiate();
        return problem;
    } catch (Exception e) {
        LOGGER.error("Error instantiating problem: {}", e.getMessage());
        return null;
    }
  }

     //Sets the output file path for the plan.
    @CommandLine.Option(
        names = {"-o", "--write-plan-to"},
        paramLabel = "<outputFullPathFile>",
        description = "If a plan is found, write it to the specified file path"
    )
    public void setOutputPathFile(final String outputFullPathFile) {
        try {
            Paths.get(outputFullPathFile);
        } catch (InvalidPathException | NullPointerException ex) {
            throw new IllegalArgumentException("Incorrect path provided");
        }
        this.outputFileName = outputFullPathFile;
    }

    // Sets the initial plan length.
    @CommandLine.Option(
        names = {"-s", "--size-plan"},
        paramLabel = "<sizePlan>",
        description = "Set the initial length of the plan"
    )
    public void setsizePlan(final int sizePlan) {
        if (sizePlan < 0) {
            throw new IllegalArgumentException("Incorrect plan length given");
        }
        this.planSize = sizePlan;
    }

   // Writes the plan to the specified file.
    public void writePlanToFile(String plan) {
        File file = new File(this.outputFileName);
        try {
            if (file.exists() && file.isFile()) {
                LOGGER.info("File {} already exists. Deleting it.", this.outputFileName);
                file.delete();
            }
            file.createNewFile();
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(plan);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to write plan to file {}", this.outputFileName);
            e.printStackTrace();
        }
    }

     // Gets the unique ID for a fluent at a specific timestep.
   
    public int getFluentID(DefaultProblem problem, Fluent state, int timeStep) {
        int idxState = problem.getFluents().indexOf(state);
        return (problem.getFluents().size() + problem.getActions().size()) * timeStep + 1 + idxState;
    }

    // Gets the unique ID for an action at a specific timestep.
    public int getActionID(DefaultProblem problem, Action action, int timeStep) {
        int idxAction = problem.getActions().indexOf(action);
        return (problem.getFluents().size() + problem.getActions().size()) * timeStep + 1 + problem.getFluents().size() + idxAction;
    }

    // Retrieves the action associated with a unique ID
    public Action getActionByIdx(DefaultProblem problem, int actionUniqueID) {
        if (actionUniqueID <= 0) {
            return null;
        }
        int idx = (actionUniqueID - 1) % (problem.getFluents().size() + problem.getActions().size());
        if (idx >= problem.getFluents().size()) {
            return problem.getActions().get(idx - problem.getFluents().size());
        }
        return null;
    }

    // Encodes the initial state as a CNF formula in DIMACS format.
    public Vec<IVecInt> encodeInitialState(final DefaultProblem problem, int planSize) {
        Vec<IVecInt> clausesInitState = new Vec<IVecInt>();
        BitVector initStatePosFluents = problem.getInitialState().getPositiveFluents();
        HashSet<Integer> fluentsNotInInitState = new HashSet<>();
        for (int i = 0; i < problem.getFluents().size(); i++) {
            fluentsNotInInitState.add(i);
        }
        for (int p = initStatePosFluents.nextSetBit(0); p >= 0; p = initStatePosFluents.nextSetBit(p + 1)) {
            Fluent f = problem.getFluents().get(p);
            fluentsNotInInitState.remove(p);
            int idxFluent = getFluentID(problem, f, 0);
            VecInt clause = new VecInt(new int[] { idxFluent });
            clausesInitState.push(clause);
            initStatePosFluents.set(p);  
        }
        for (Integer stateNotInInitState : fluentsNotInInitState) {
            VecInt clause = new VecInt(new int[] { -(stateNotInInitState + 1) });
            clausesInitState.push(clause);
        }
        LOGGER.debug("Initial state clauses: {}", clausesInitState);
        return clausesInitState;
    }

    // Encodes the goal state as a CNF formula in DIMACS format.
    public Vec<IVecInt> encodeFinalState(final DefaultProblem problem, int planSize) {
        Vec<IVecInt> clausesGoalState = new Vec<IVecInt>();
        BitVector goalPosFluents = problem.getGoal().getPositiveFluents();
        for (int p = goalPosFluents.nextSetBit(0); p >= 0; p = goalPosFluents.nextSetBit(p + 1)) {
            Fluent f = problem.getFluents().get(p);
            int idxFluent = getFluentID(problem, f, planSize);
            VecInt clause = new VecInt(new int[] { idxFluent });
            clausesGoalState.push(clause);
            goalPosFluents.set(p);
        }
        return clausesGoalState;
    }

    // Encodes the actions as a CNF formula in DIMACS format.
    public Vec<IVecInt> encodeActions(final DefaultProblem problem, int planSize) {
        Vec<IVecInt> clausesActions = new Vec<IVecInt>();
        for (int timeStep = 0; timeStep < planSize; timeStep++) {
            for (Action action : problem.getActions()) {
                int actionUniqueIDforTimeStep = getActionID(problem, action, timeStep);
                BitVector precondPos = action.getPrecondition().getPositiveFluents();
                for (int p = precondPos.nextSetBit(0); p >= 0; p = precondPos.nextSetBit(p + 1)) {
                    Fluent f = problem.getFluents().get(p);
                    int fluentUniqueIDforTimeStep = getFluentID(problem, f, timeStep);
                    VecInt clause = new VecInt(new int[] { -actionUniqueIDforTimeStep, fluentUniqueIDforTimeStep });
                    clausesActions.push(clause);
                    precondPos.set(p);
                }
                BitVector precondNeg = action.getPrecondition().getNegativeFluents();
                for (int p = precondNeg.nextSetBit(0); p >= 0; p = precondNeg.nextSetBit(p + 1)) {
                    Fluent f = problem.getFluents().get(p);
                    int idxFluent = getFluentID(problem, f, timeStep);
                    VecInt clause = new VecInt(new int[] { -actionUniqueIDforTimeStep, -idxFluent });
                    clausesActions.push(clause);
                    precondNeg.set(p);
                }
                BitVector effectPos = action.getUnconditionalEffect().getPositiveFluents();
                for (int p = effectPos.nextSetBit(0); p >= 0; p = effectPos.nextSetBit(p + 1)) {
                    Fluent f = problem.getFluents().get(p);
                    int idxFluent = getFluentID(problem, f, timeStep + 1);
                    VecInt clause = new VecInt(new int[] { -actionUniqueIDforTimeStep, idxFluent });
                    clausesActions.push(clause);
                    effectPos.set(p);
                }
                BitVector effectNeg = action.getUnconditionalEffect().getNegativeFluents();
                for (int p = effectNeg.nextSetBit(0); p >= 0; p = effectNeg.nextSetBit(p + 1)) {
                    Fluent f = problem.getFluents().get(p);
                    int idxFluent = getFluentID(problem, f, timeStep + 1);
                    VecInt clause = new VecInt(new int[] { -actionUniqueIDforTimeStep, -idxFluent });
                    clausesActions.push(clause);
                    effectNeg.set(p);
                }
            }
        }
        LOGGER.debug("Action clauses: {}", clausesActions);
        return clausesActions;
    }

   // Encodes the explanatory frame axioms as a CNF formula in DIMACS format
    public Vec<IVecInt> encodeFrameAxioms(final DefaultProblem problem, int planSize) {
        Vec<IVecInt> clausesExplanatoryFrameAxioms = new Vec<IVecInt>();
        @SuppressWarnings("unchecked")
        ArrayList<Action>[] positiveEffectOnFluent = new ArrayList[problem.getFluents().size()];
        @SuppressWarnings("unchecked")
        ArrayList<Action>[] negativeEffectOnFluent = new ArrayList[problem.getFluents().size()];
        for (int i = 0; i < problem.getFluents().size(); i++) {
            positiveEffectOnFluent[i] = new ArrayList<>();
            negativeEffectOnFluent[i] = new ArrayList<>();
        }
        for (Action action : problem.getActions()) {
            BitVector effectPos = action.getUnconditionalEffect().getPositiveFluents();
            for (int p = effectPos.nextSetBit(0); p >= 0; p = effectPos.nextSetBit(p + 1)) {
                positiveEffectOnFluent[p].add(action);
                effectPos.set(p);
            }
            BitVector effectNeg = action.getUnconditionalEffect().getNegativeFluents();
            for (int p = effectNeg.nextSetBit(0); p >= 0; p = effectNeg.nextSetBit(p + 1)) {
                negativeEffectOnFluent[p].add(action);
                effectNeg.set(p);
            }
        }
        for (int stateIdx = 0; stateIdx < problem.getFluents().size(); stateIdx++) {
            for (int timeStep = 0; timeStep < planSize; timeStep++) {
                if (!positiveEffectOnFluent[stateIdx].isEmpty()) {
                    Fluent f = problem.getFluents().get(stateIdx);
                    VecInt clause = new VecInt();
                    clause.push(getFluentID(problem, f, timeStep));
                    clause.push(-getFluentID(problem, f, timeStep + 1));
                    for (Action action : positiveEffectOnFluent[stateIdx]) {
                        clause.push(getActionID(problem, action, timeStep));
                    }
                    clausesExplanatoryFrameAxioms.push(clause);
                }
                if (!negativeEffectOnFluent[stateIdx].isEmpty()) {
                    Fluent f = problem.getFluents().get(stateIdx);
                    VecInt clause = new VecInt();
                    clause.push(-getFluentID(problem, f, timeStep));
                    clause.push(getFluentID(problem, f, timeStep + 1));
                    for (Action action : negativeEffectOnFluent[stateIdx]) {
                        clause.push(getActionID(problem, action, timeStep));
                    }
                    clausesExplanatoryFrameAxioms.push(clause);
                }
            }
        }
        return clausesExplanatoryFrameAxioms;
    }

   // Encodes the complete exclusion axioms as a CNF formula in DIMACS format
    public Vec<IVecInt> encodeExclusionAxioms(final DefaultProblem problem, int planSize) {
        Vec<IVecInt> clausesCompleteExclusionAxioms = new Vec<IVecInt>();
        for (int iteratorAction1 = 0; iteratorAction1 < problem.getActions().size(); iteratorAction1++) {
            for (int iteratorAction2 = 0; iteratorAction2 < iteratorAction1; iteratorAction2++) {
                Action action1 = problem.getActions().get(iteratorAction1);
                Action action2 = problem.getActions().get(iteratorAction2);
                int initAction1Idx = getActionID(problem, action1, 0);
                int initAction2Idx = getActionID(problem, action2, 0);
                int offsetToNextActionIdx = problem.getActions().size() + problem.getFluents().size();
                for (int timeStep = 0; timeStep < planSize; timeStep++) {
                    int offset = offsetToNextActionIdx * timeStep;
                    VecInt clause = new VecInt(new int[] { -(initAction1Idx + offset), -(initAction2Idx + offset) });
                    clausesCompleteExclusionAxioms.push(clause);
                }
            }
        }
        return clausesCompleteExclusionAxioms;
    }
     
    //Solves the SAT problem using SAT4J.
    public int[] solverSAT(Vec<IVecInt> allClauses, DefaultProblem problem) throws TimeoutException {
        final int MAXVAR = (problem.getFluents().size() + problem.getActions().size()) * this.planSize + problem.getFluents().size();
        LOGGER.debug("Number of clauses: {}", allClauses.size());
        ISolver solver = SolverFactory.newDefault();
        solver.newVar(MAXVAR);
        solver.setExpectedNumberOfClauses(allClauses.size());
        try {
            solver.addAllClauses(allClauses);
        } catch (ContradictionException e) {
            return null;
        }
        IProblem problemSAT = solver;
        try {
            if (problemSAT.isSatisfiable()) {
                LOGGER.info("Problem is satisfiable");
                return problemSAT.model();
            } else {
                LOGGER.info("Problem is not satisfiable");
                return null;
            }
        } catch (TimeoutException e) {
            LOGGER.error("Solver timeout");
            throw new TimeoutException("Timeout while finding a model");
        }
    }

    //Encodes the problem as a CNF formula in DIMACS format
    public Vec<IVecInt> encodeProblemToCNF(DefaultProblem problem, int planSize) {
        LOGGER.info("Encoding initial state");
        Vec<IVecInt> clausesInitState = encodeInitialState(problem, planSize);
        LOGGER.info("Encoding goal state");
        Vec<IVecInt> clausesGoalState = encodeFinalState(problem, planSize);
        LOGGER.info("Encoding actions");
        Vec<IVecInt> clausesActions = encodeActions(problem, planSize);
        LOGGER.info("Encoding explanatory frame axioms");
        Vec<IVecInt> clausesExplanatoryFrameAxioms = encodeFrameAxioms(problem, planSize);
        LOGGER.info("Encoding complete exclusion axioms");
        Vec<IVecInt> clausesCompleteExclusionAxioms = encodeExclusionAxioms(problem, planSize);
        Vec<IVecInt> allClauses = new Vec<IVecInt>(
            clausesInitState.size() + clausesGoalState.size() + clausesActions.size() +
            clausesExplanatoryFrameAxioms.size() + clausesCompleteExclusionAxioms.size()
        );
        clausesInitState.copyTo(allClauses);
        clausesGoalState.copyTo(allClauses);
        clausesActions.copyTo(allClauses);
        clausesExplanatoryFrameAxioms.copyTo(allClauses);
        clausesCompleteExclusionAxioms.copyTo(allClauses);
        LOGGER.debug("Initial state clauses: {}", clausesInitState.size());
        LOGGER.debug("Goal state clauses: {}", clausesGoalState.size());
        LOGGER.debug("Action clauses: {}", clausesActions.size());
        LOGGER.debug("Explanatory frame axioms clauses: {}", clausesExplanatoryFrameAxioms.size());
        LOGGER.debug("Complete exclusion axioms clauses: {}", clausesCompleteExclusionAxioms.size());
        return allClauses;
    }

    // Constructs the plan from the SAT model
    public Plan planFromModel(int[] model, DefaultProblem problem) {
        Plan plan = new SequentialPlan();
        int idxActionInPlan = 0;
        for (int idx : model) {
            Action a = getActionByIdx(problem, idx);
            if (a != null) {
                plan.add(idxActionInPlan, a);
                idxActionInPlan++;
            }
        }
        return plan;
    }

    //Searches for a solution plan using a SAT solver.
    @Override
    public Plan solve(final Problem problem) {
        if (!(problem instanceof DefaultProblem)) {
            LOGGER.error("Invalid problem type, expected DefaultProblem");
            return null;
        }
        DefaultProblem defaultProblem = (DefaultProblem) problem;
        int[] model;
        while (true) {
            LOGGER.info("Encoding model for plan size: {}", this.planSize);
            final long beginEncodeTime = System.currentTimeMillis();
            Vec<IVecInt> allClauses = encodeProblemToCNF(defaultProblem, this.planSize);
            final long endEncodeTime = System.currentTimeMillis();
            this.toEncodeTime += (endEncodeTime - beginEncodeTime);
            LOGGER.info("Number of clauses: {}", allClauses.size());
            final long beginSolveTime = System.currentTimeMillis();
            LOGGER.info("Launching SAT solver");
            try {
                model = solverSAT(allClauses, defaultProblem);
            } catch (TimeoutException e) {
                final long endSolveTime = System.currentTimeMillis();
                this.toSearchTime += (endSolveTime - beginSolveTime);
                return null;
            }
            final long endSolveTime = System.currentTimeMillis();
            this.toSearchTime += (endSolveTime - beginSolveTime);
            if (model == null) {
                LOGGER.info("No solution found with plan size = {}. Doubling plan size.", this.planSize);
                this.planSize *= 2;
            } else {
                break;
            }
        }
        Plan plan = planFromModel(model, defaultProblem);
        if (outputFileName != null) {
           // writePlanToFile(defaultProblem.toString(plan));
         writePlanToFile( toPDDLPlanFormat((SequentialPlan) plan));
        }
        LOGGER.info("Encoding time: {} ms", this.toEncodeTime);
        LOGGER.info("Solving time: {} ms", this.toSearchTime);
        return plan;
    }
    
    public String toPDDLPlanFormat(SequentialPlan plan) {
    StringBuilder sb = new StringBuilder();
        List<Action> actions = plan.actions();
    for (int i = 0; i < actions.size(); i++) {
         Action action = actions.get(i);
        sb.append("(").append(action.getName());
        for (int param : action.getParameters()) {
            sb.append(" ").append(param);
        }
        sb.append(")\n");
    }
    return sb.toString();
}
   
    // Main method of the SAT planner.
    public static void main(String[] args) {
        try {
            final SAT_Planner planner = new SAT_Planner();
            CommandLine cmd = new CommandLine(planner);
            cmd.execute(args);
        } catch (IllegalArgumentException e) {
            LOGGER.fatal("Error: {}", e.getMessage());
        }
    }
}
