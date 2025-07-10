import subprocess
import time
import csv
import os

PDDL_DIR = "benchmarks"
DOMAINS = ["blocksworld", "depots", "gripper", "logistics"]

results = []

for domain in DOMAINS:
    domain_path = os.path.join(PDDL_DIR, domain)
    domain_file = os.path.join(domain_path, "domain.pddl")

    # Prendre seulement les 20 premiers fichiers "pXX.pddl"
    problem_files = sorted([
        f for f in os.listdir(domain_path)
        if f.startswith("p") and f.endswith(".pddl")
    ])[:5]

    for problem in problem_files:
        problem_file = os.path.join(domain_path, problem)

        # --- SAT Planner ---
        t0 = time.time()
        sat_process = subprocess.run([
            "C:\\Users\\Admin\\Downloads\\apache-maven-3.9.10-bin\\apache-maven-3.9.10\\bin\\mvn.cmd",
            "exec:java",
            "-Dexec.mainClass=com.mycompany.sat_planner.SAT_Planner",
            f"-Dexec.args={domain_file} {problem_file}"
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        t1 = time.time()
        sat_duration = round(t1 - t0, 3)
        sat_plan_lines = [l for l in sat_process.stdout.splitlines() if l.strip().startswith("0:")]
        sat_makespan = len(sat_plan_lines) if sat_plan_lines else 0

        # --- HSP Planner ---
        t0 = time.time()
        hsp_process = subprocess.run([
            "C:\\Users\\Admin\\Downloads\\apache-maven-3.9.10-bin\\apache-maven-3.9.10\\bin\\mvn.cmd",
            "exec:java",
            "-Dexec.mainClass=fr.uga.pddl4j.planners.statespace.HSP",
            f"-Dexec.args={domain_file} {problem_file}"
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        t1 = time.time()
        hsp_duration = round(t1 - t0, 3)
        hsp_plan_lines = [l for l in hsp_process.stdout.splitlines() if l.strip().startswith("0:")]
        hsp_makespan = len(hsp_plan_lines) if hsp_plan_lines else 0

        # Append result
        results.append([
            domain,
            problem,
            sat_makespan,
            sat_duration,
            hsp_makespan,
            hsp_duration
        ])

# --- Écriture du CSV au format demandé ---
with open("comparison_results.csv", "w", newline="") as csvfile:
    writer = csv.writer(csvfile)
    writer.writerow(["domain", "problem", "sat_makespan", "sat_time", "hsp_makespan", "hsp_time"])
    writer.writerows(results)

print(" CSV exporté au format demandé : comparison_results.csv")
