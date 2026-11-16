// ===================== RÉGLAGES =====================
String classeRosette = "rosette"   // classe des rosettes
String m1 = "SOX2"                 // 1er marqueur (tel qu'écrit dans la classe des cellules)
String m2 = "KI67"                 // 2e marqueur (Ki67 ou KI67, les deux sont acceptés)
int minCellulesCoupe = 1000        // une "coupe" avec moins de cellules = débris, ignorée
def ordreConditions = ["Ctrl-no-iMG", "C9-no-iMG", "GRN-no-iMG", "Ctrl-iMG-iso", "C9-iMG-iso", "GRN-iMG-iso"]
// ====================================================

def motif = java.util.regex.Pattern.compile('(?i)org\\s*(\\d+)')
def numeroOrg = { obj ->
    def m = motif.matcher(obj.getName() ?: "")
    if (m.find()) return m.group(1) as Integer
    def cl = obj.getPathClass()?.toString() ?: ""
    def m2b = motif.matcher(cl)
    if (m2b.find()) return m2b.group(1) as Integer
    return null
}
def estRosette = { obj -> (obj.getPathClass()?.toString() ?: "").equalsIgnoreCase(classeRosette) }
def estCoupe = { obj ->
    if (!obj.isAnnotation() || obj.getROI() == null || !obj.getROI().isArea()) return false
    def cl = obj.getPathClass()?.toString() ?: ""
    if (cl.equalsIgnoreCase(classeRosette)) return false
    return cl == "Org*" || cl.matches('(?i)org\\s*\\d+\\*?') || numeroOrg(obj) != null
}
def normaliser = { String s -> s.replaceAll('(?i)-Ctr(?=-|\\z)', '-Ctrl') }
def infoLame = { String nom ->
    def base = nom.replaceFirst('(?i)\\.vsi.*\\z', '').replaceFirst('(?i)^(training|validation)_', '')
    def m = java.util.regex.Pattern.compile('-([AB])(_\\d+)?\\z').matcher(base)
    if (m.find()) base = base.substring(0, m.start())
    base = normaliser(base)
    def cond = base.replaceFirst('^Image_', '').replaceFirst('^D\\d+-J\\d+-', '').replace("BOC-", "")
    return [groupe: base, condition: cond]
}
// "C9-iMG-iso" -> génotype C9, iMG "avec iMG iso", libellé "C9 avec iMG iso"
def decrireCondition = { String cond ->
    def parties = cond.split("-").toList()
    def genotype = parties[0]
    def reste = parties.drop(1).join("-")
    def img
    if (reste.matches('(?i)no-iMG.*')) {
        img = "sans iMG"
    } else if (reste.matches('(?i)iMG.*')) {
        def type = reste.replaceFirst('(?i)^iMG-?', '').replace("-", " ").trim()
        img = type ? "avec iMG " + type : "avec iMG"
    } else {
        img = reste.replace("-", " ")
    }
    [genotype: genotype, img: img, libelle: (genotype + " " + img).trim()]
}

// Tous les descendants d'un objet dans la hiérarchie
def descendants
descendants = { obj ->
    def liste = []
    obj.getChildObjects().each { e ->
        liste << e
        liste.addAll(descendants(e))
    }
    liste
}
def positive = { cell, String marqueur ->
    def pc = cell.getPathClass()
    pc != null && pc.toString().split(":").collect { it.trim() }.any { it.equalsIgnoreCase(marqueur) }
}
def pct = { int a, int b -> String.format(Locale.FRANCE, "%.2f", b > 0 ? 100.0 * a / b : 0.0) }

// ---------- 1. Comptage par organoïde (lames A + B additionnées) ----------
def totaux = [:]
for (entry in getProject().getImageList()) {
    def nomImg = entry.getImageName()
    if (nomImg.startsWith("TRAINING") || nomImg.startsWith("Sparse")) continue
    def hier = entry.readHierarchy()
    def info = infoLame(nomImg)
    def toutesRosettes = hier.getAnnotationObjects().findAll { estRosette(it) && it.getROI() != null && it.getROI().isArea() }
    int nCoupes = 0
    int nRosettes = 0

    hier.getAnnotationObjects().findAll { estCoupe(it) }.each { coupe ->
        def gCoupe = coupe.getROI().getGeometry()
        def sousCoupe = descendants(coupe)

        // Rosettes de la coupe : sous la coupe OU dont le centre est dans la coupe
        def rosettes = new LinkedHashSet()
        rosettes.addAll(sousCoupe.findAll { estRosette(it) })
        rosettes.addAll(toutesRosettes.findAll { gCoupe.contains(it.getROI().getGeometry().getCentroid()) })

        // Cellules des rosettes : lues dans la hiérarchie, sous chaque rosette
        def cellsRos = new LinkedHashSet()
        rosettes.each { r -> cellsRos.addAll(descendants(r).findAll { it.isCell() }) }

        // Cellules de la coupe : sous la coupe + sous ses rosettes (sans doublon)
        def cells = new LinkedHashSet()
        cells.addAll(sousCoupe.findAll { it.isCell() })
        cells.addAll(cellsRos)
        if (cells.size() < minCellulesCoupe) return

        def num = numeroOrg(coupe)
        def cle = info.groupe + "|" + num
        if (!totaux.containsKey(cle))
            totaux[cle] = [cond: info.condition, num: num, coupes: 0, rosettes: 0,
                           n: 0, s: 0, k: 0, sk: 0, rn: 0, rs: 0, rk: 0, rsk: 0]
        def t = totaux[cle]
        t.coupes = t.coupes + 1
        t.rosettes = t.rosettes + rosettes.size()
        t.n   = t.n   + cells.size()
        t.s   = t.s   + cells.count { positive(it, m1) }
        t.k   = t.k   + cells.count { positive(it, m2) }
        t.sk  = t.sk  + cells.count { positive(it, m1) && positive(it, m2) }
        t.rn  = t.rn  + cellsRos.size()
        t.rs  = t.rs  + cellsRos.count { positive(it, m1) }
        t.rk  = t.rk  + cellsRos.count { positive(it, m2) }
        t.rsk = t.rsk + cellsRos.count { positive(it, m1) && positive(it, m2) }
        nCoupes++
        nRosettes += rosettes.size()
    }
    println "OK : " + nomImg + " (" + nCoupes + " coupes, " + nRosettes + " rosettes sur " + toutesRosettes.size() + " dans la lame)"
    hier = null
}

// ---------- 2. Tableau par organoïde ----------
// Noms des categories
String pm = m1 + "+_" + m2 + "-"   // SOX2+_KI67-
String mp = m2 + "+_" + m1 + "-"   // KI67+_SOX2-
String pp = m1 + "+_" + m2 + "+"   // SOX2+_KI67+
String mm = m1 + "-_" + m2 + "-"   // SOX2-_KI67-
String SLASH = String.valueOf((char) 47)   // caractere "barre oblique"

def colonnes = [
    "Condition", "Genotype", "iMG", "Organoide", "Nb_coupes", "Nb_rosettes", "DAPI+",
    // Organoïde entier
    pm, mp, pp, mm,
    "% " + pm, "% " + mp, "% " + pp,
    // Dans les rosettes
    "DAPI+_dans_rosettes", "% DAPI+_dans_rosettes",
    m1 + "+_dans_rosettes", m2 + "+_dans_rosettes",
    pm + "_dans_rosettes", mp + "_dans_rosettes", pp + "_dans_rosettes",
    "% " + pm + "_dans_rosettes", "% " + mp + "_dans_rosettes", "% " + pp + "_dans_rosettes",
    "%DAPI+" + SLASH + m1 + "_dans_rosette",
    // Hors rosettes
    "DAPI+_hors_rosettes", "% DAPI+_hors_rosettes",
    pm + "_hors_rosettes", mp + "_hors_rosettes", pp + "_hors_rosettes",
    "% " + pm + "_hors_rosettes", "% " + mp + "_hors_rosettes", "% " + pp + "_hors_rosettes",
    "%DAPI+" + SLASH + m1 + "_hors_rosette"
]
def lignes = [colonnes.join(";")]

def ordre = { String c ->
    int i = ordreConditions.findIndexOf { it.equalsIgnoreCase(c) }
    return i < 0 ? 1000 : i
}
def liste = totaux.values().toList()
liste.sort { a, b ->
    int r = ordre(a.cond) <=> ordre(b.cond)
    if (r == 0) r = a.cond <=> b.cond
    if (r == 0) r = (a.num ?: 999) <=> (b.num ?: 999)
    return r
}
liste.each { t ->
    def d = decrireCondition(t.cond)
    // Organoïde entier
    int nPM = t.s - t.sk
    int nMP = t.k - t.sk
    int nPP = t.sk
    int nMM = t.n - (t.s + t.k - t.sk)
    // Dans les rosettes
    int rPM = t.rs - t.rsk
    int rMP = t.rk - t.rsk
    int rPP = t.rsk
    // Hors rosettes = organoïde entier - rosettes
    int hn = t.n - t.rn
    int hS = t.s - t.rs
    int hPM = nPM - rPM
    int hMP = nMP - rMP
    int hPP = nPP - rPP
    def valeurs = [
        d.libelle, d.genotype, d.img, (t.num == null ? "Org ?" : "Org " + t.num), t.coupes, t.rosettes, t.n,
        nPM, nMP, nPP, nMM,
        pct(nPM, t.n), pct(nMP, t.n), pct(nPP, t.n),
        t.rn, pct(t.rn, t.n),
        t.rs, t.rk,
        rPM, rMP, rPP,
        pct(rPM, t.rn), pct(rMP, t.rn), pct(rPP, t.rn),
        pct(t.rs, t.rn),
        hn, pct(hn, t.n),
        hPM, hMP, hPP,
        pct(hPM, hn), pct(hMP, hn), pct(hPP, hn),
        pct(hS, hn)
    ]
    lignes << valeurs.join(";")
}

// ---------- 3. Écriture ----------
def dossier = buildFilePath(PROJECT_BASE_DIR, "resultats")
mkdirs(dossier)
def texte = "\uFEFF" + lignes.join("\n")
def fichier = new File(buildFilePath(dossier, "cellules_par_organoide.csv"))
try {
    fichier.setText(texte, "UTF-8")
    println "Écrit : " + fichier.getName()
} catch (Exception e) {
    def stamp = new Date().format("yyyyMMdd_HHmmss")
    def copie = new File(buildFilePath(dossier, "cellules_par_organoide_" + stamp + ".csv"))
    copie.setText(texte, "UTF-8")
    println "ATTENTION : fichier ouvert (Excel ?), résultats écrits dans " + copie.getName()
}
println "Terminé : " + (lignes.size() - 1) + " organoïdes"
