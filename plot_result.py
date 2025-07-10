import pandas as pd
import matplotlib.pyplot as plt

# Charger les données CSV
data = pd.read_csv("comparison_results1.csv")

# Nettoyer les noms des colonnes et des domaines
data.columans = [col.strip() for col in data.columns]
data['domain'] = data['domain'].str.strip()
data['problem'] = data['problem'].str.strip()

# Récupérer les domaines uniques
domains = data['domain'].unique()

# Tracer les 4 figures pour le temps (SAT vs HSP)
for domain in domains:
    subset = data[data['domain'] == domain]

    plt.figure(figsize=(10, 6))
    plt.title(f"{domain.capitalize()} - Temps d'exécution (SAT vs HSP)")
    bar_width = 0.35
    x = range(len(subset))

    plt.bar([i - bar_width/2 for i in x], subset['sat_time'], width=bar_width, label='SAT', color='blue')
    plt.bar([i + bar_width/2 for i in x], subset['hsp_time'], width=bar_width, label='HSP', color='green')

    plt.xticks(ticks=x, labels=subset['problem'], rotation=45)
    plt.xlabel('Problème')
    plt.ylabel('Temps (secondes)')
    plt.legend()
    plt.tight_layout()
    plt.savefig(f"figures/{domain}_time_comparison.png")
    plt.close()

# Tracer les 4 figures pour le makespan (SAT vs HSP)
for domain in domains:
    subset = data[data['domain'] == domain]

    plt.figure(figsize=(10, 6))
    plt.title(f"{domain.capitalize()} - Taille du plan (Makespan SAT vs HSP)")
    bar_width = 0.35
    x = range(len(subset))

    plt.bar([i - bar_width/2 for i in x], subset['sat_makespan'], width=bar_width, label='SAT', color='orange')
    plt.bar([i + bar_width/2 for i in x], subset['hsp_makespan'], width=bar_width, label='HSP', color='purple')

    plt.xticks(ticks=x, labels=subset['problem'], rotation=45)
    plt.xlabel('Problème')
    plt.ylabel('Taille du plan (makespan)')
    plt.legend()
    plt.tight_layout()
    plt.savefig(f"figures/{domain}_makespan_comparison.png") 
    plt.close()

print("Tous les graphiques ont été générés avec succès.")
