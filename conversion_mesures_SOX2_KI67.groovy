// ===================== CONVERSION DES MESURES POUR L'OBJECT CLASSIFIER =====================
// Cree, pour chaque cellule, les mesures au format attendu par le classifieur :
//     "SOX2: Nucleus: Mean", "KI67: Cytoplasm: Max", ...
// a partir des mesures existantes, quel que soit leur format :
//     StarDist      : "Nucleus: SOX2: Mean"
//     classique     : "SOX2: Nucleus: Mean"
//     ancien format : "Nucleus: SOX2 mean"
// Les canaux dont le nom contient "sox2" ou "ki67" (majuscules ou minuscules) sont reconnus seuls.
//
// Image ouverte : Run      |      Toutes les images : Run > Run for project
// ===========================================================================================

// Si un canal porte un autre nom (ex. le nom du fluorochrome), indique-le ici :
// nom du canal dans TES mesures -> nom attendu par le classifieur
def correspondancesManuelles = [:]   // exemple : ["Cy5": "SOX2", "FITC": "KI67"]

// Nom de l'object classifier a appliquer juste apres ("" = ne pas l'appliquer)
String classifieur = ""
// ===========================================================================================

def compartiments = ["Nucleus", "Cytoplasm", "Membrane", "Cell"]
def stats = ["Mean", "Median", "Min", "Max", "Std.Dev."]
def statsAncien = ["mean": "Mean", "median": "Median", "min": "Min", "max": "Max", "std dev": "Std.Dev."]

// Nom attendu par le classifieur pour un canal
def attendu = { String canal ->
    if (correspondancesManuelles.containsKey(canal)) return correspondancesManuelles[canal]
    def c = canal.toLowerCase().replace("-", "").replace(" ", "")
    if (c.contains("sox2")) return "SOX2"
    if (c.contains("ki67")) return "KI67"
    return canal
}

// Decoupe un nom de mesure en [compartiment, canal, statistique], ou null si ce n'est pas une intensite
def decouper = { String nom ->
    def p = nom.split(": ")
    if (p.size() == 3 && compartiments.contains(p[0]) && stats.contains(p[2])) return [p[0], p[1], p[2]]   // StarDist
    if (p.size() == 3 && compartiments.contains(p[1]) && stats.contains(p[2])) return [p[1], p[0], p[2]]   // classique
    if (p.size() == 2 && compartiments.contains(p[0])) {                                                  // ancien format
        def m = java.util.regex.Pattern.compile('(.+) (mean|median|min|max|std dev)').matcher(p[1])
        if (m.matches()) return [p[0], m.group(1), statsAncien[m.group(2)]]
    }
    return null
}

def nomImage = getProjectEntry() != null ? getProjectEntry().getImageName() : "image ouverte"
def cellules = getCellObjects()
if (cellules.isEmpty()) {
    println "Aucune cellule sur : " + nomImage
    return
}

// Correspondance "mesure existante -> mesure attendue", calculee une fois a partir de la 1re cellule
def correspondance = [:]
def canauxVus = new LinkedHashSet()
cellules[0].getMeasurementList().getMeasurementNames().each { nom ->
    def d = decouper(nom)
    if (d == null) return
    canauxVus << d[1]
    def cible = attendu(d[1]) + ": " + d[0] + ": " + d[2]
    if (cible != nom) correspondance[nom] = cible
}
println nomImage
println "   Canaux trouves dans les mesures : " + canauxVus + "  ->  " + canauxVus.collect { attendu(it) }

// Copie des valeurs, cellule par cellule
int nCellules = 0
cellules.each { c ->
    def ml = c.getMeasurementList()
    correspondance.each { ancienNom, nouveauNom ->
        double v = ml.get(ancienNom)
        if (!Double.isNaN(v)) ml.put(nouveauNom, v)
    }
    ml.close()
    nCellules++
}
fireHierarchyUpdate()
println "   " + nCellules + " cellules, " + correspondance.size() + " mesures creees par cellule"

def cibles = canauxVus.collect { attendu(it) }
if (!cibles.contains("SOX2") || !cibles.contains("KI67"))
    println "   ATTENTION : SOX2 ou KI67 introuvable. Renseigne correspondancesManuelles avec les canaux ci-dessus."

// Cellules sans cytoplasme : on recopie les valeurs du noyau dans les mesures "Cytoplasm" manquantes
int nSansCyto = 0
def marqueurs = cibles.findAll { it == "SOX2" || it == "KI67" }
cellules.each { c ->
    def ml = c.getMeasurementList()
    boolean corrige = false
    marqueurs.each { m ->
        stats.each { s ->
            if (Double.isNaN(ml.get(m + ": Cytoplasm: " + s))) {
                double v = ml.get(m + ": Nucleus: " + s)
                if (!Double.isNaN(v)) { ml.put(m + ": Cytoplasm: " + s, v); corrige = true }
            }
        }
    }
    ml.close()
    if (corrige) nSansCyto++
}
if (nSansCyto > 0) println "   " + nSansCyto + " cellule(s) sans cytoplasme : valeurs du noyau recopiees"

// Application du classifieur (optionnel)
if (classifieur) {
    runObjectClassifier(classifieur)
    println "   Classifieur '" + classifieur + "' applique"
}
