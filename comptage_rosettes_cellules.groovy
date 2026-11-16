// ===================== COMPTAGE DES CELLULES PAR ORGANOIDE (1 seul script) =====================
// A lancer avec  Run > Run for project (without save), en selectionnant toutes les lames.
// Pour chaque lame : ecrit un petit fichier dans resultats/par_lame/,
// puis met a jour le tableau final resultats/cellules_par_organoide.csv (toutes lames confondues).
// =================================================================================================
String classeRosette = "rosette"   // classe des rosettes
String m1 = "SOX2"                 // 1er marqueur (tel qu'ecrit dans la classe des cellules)
String m2 = "KI67"                 // 2e marqueur (Ki67 ou KI67, les deux sont acceptes)
int minCellulesCoupe = 1000        // une "coupe" avec moins de cellules = debris, ignoree
def lamesIgnorees = ["TRAINING", "Sparse", "SEC-"]   // lames ignorees (debut du nom)
def ordreConditions = ["Ctrl-no-iMG", "C9-no-iMG", "GRN-no-iMG", "Ctrl-iMG-iso", "C9-iMG-iso", "GRN-iMG-iso"]
// =================================================================================================

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
    base = base.replaceFirst('^.*?(?=Image_)', '')   // enleve un prefixe avant "Image_" (ex. "SEC-")
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

def positive = { cell, String marqueur ->
    def pc = cell.getPathClass()
    pc != null && pc.toString().split(":").collect { it.trim() }.any { it.equalsIgnoreCase(marqueur) }
}
def pct = { int a, int b -> String.format(Locale.FRANCE, "%.2f", b > 0 ? 100.0 * a / b : 0.0) }


def nomImg = getProjectEntry().getImageName()
if (lamesIgnorees.any { nomImg.startsWith(it) }) { println "Ignoree : " + nomImg; return }

def hier = getCurrentHierarchy()
def info = infoLame(nomImg)
def coupes = hier.getAnnotationObjects().findAll { estCoupe(it) }
def rosettes = hier.getAnnotationObjects().findAll { estRosette(it) && it.getROI() != null && it.getROI().isArea() }

// Chaque rosette est rattachee a la coupe qui la contient (hierarchie, sinon position)
def coupeDeRosette = [:]
rosettes.each { r ->
    def p = r.getParent()
    while (p != null && !estCoupe(p)) p = p.getParent()
    if (p == null) {
        def centre = r.getROI().getGeometry().getCentroid()
        p = coupes.find { it.getROI().getGeometry().contains(centre) }
    }
    if (p != null) coupeDeRosette[r] = p
}

def cpt = [:]
coupes.each { c -> cpt[c] = [n: 0, s: 0, k: 0, sk: 0, rn: 0, rs: 0, rk: 0, rsk: 0, ros: 0] }
coupeDeRosette.each { r, c -> cpt[c].ros = cpt[c].ros + 1 }

def cacheClasse = [:]
for (cell in hier.getCellObjects()) {
    def rosette = null
    def coupe = null
    def p = cell.getParent()
    while (p != null) {
        if (rosette == null && estRosette(p)) rosette = p
        else if (estCoupe(p)) { coupe = p; break }
        p = p.getParent()
    }
    if (coupe == null && rosette != null) coupe = coupeDeRosette[rosette]
    if (coupe == null) continue
    def pc = cell.getPathClass()
    def pos = cacheClasse[pc]
    if (pos == null) {
        pos = [positive(cell, m1), positive(cell, m2)]
        cacheClasse[pc] = pos
    }
    boolean s = pos[0]
    boolean k = pos[1]
    def t = cpt[coupe]
    t.n++
    if (s) t.s++
    if (k) t.k++
    if (s && k) t.sk++
    if (rosette != null) {
        t.rn++
        if (s) t.rs++
        if (k) t.rk++
        if (s && k) t.rsk++
    }
}

// Une ligne par coupe
def lignesLame = ["Image;Groupe;Condition;Organoide;Rosettes;n;s;k;sk;rn;rs;rk;rsk"]
cpt.each { coupe, c ->
    def num = numeroOrg(coupe)
    lignesLame << [nomImg, info.groupe, info.condition, (num == null ? "" : num), c.ros,
               c.n, c.s, c.k, c.sk, c.rn, c.rs, c.rk, c.rsk].join(";")
}
def dossierLame = buildFilePath(PROJECT_BASE_DIR, "resultats", "par_lame")
mkdirs(dossierLame)
def nomFichier = nomImg.replaceAll('[^A-Za-z0-9_-]', '_') + ".csv"
new File(buildFilePath(dossierLame, nomFichier)).setText(lignesLame.join("\n"), "UTF-8")
println "OK : " + nomImg + " (" + coupes.size() + " coupes, " + rosettes.size() + " rosettes, " + hier.getCellObjects().size() + " cellules)"

// ===================== MISE A JOUR DU TABLEAU FINAL (toutes les lames deja traitees) =====================

def dossierLames = new File(buildFilePath(PROJECT_BASE_DIR, "resultats", "par_lame"))
def fichiers = dossierLames.exists() ? dossierLames.listFiles().findAll { it.getName().endsWith(".csv") } : []
if (fichiers.isEmpty()) { println "Aucun fichier dans " + dossierLames + " : lance d'abord l'etape 1"; return }

def totaux = [:]
fichiers.each { f ->
    def lignesF = f.getText("UTF-8").readLines()
    lignesF.drop(1).each { ligne ->
        def v = ligne.split(";", -1)
        int n = v[5] as int
        if (n < minCellulesCoupe) return
        def num = v[3] ? (v[3] as Integer) : null
        def cle = v[1] + "|" + num
        if (!totaux.containsKey(cle))
            totaux[cle] = [cond: v[2], num: num, coupes: 0, rosettes: 0,
                           n: 0, s: 0, k: 0, sk: 0, rn: 0, rs: 0, rk: 0, rsk: 0]
        def t = totaux[cle]
        t.coupes = t.coupes + 1
        t.rosettes = t.rosettes + (v[4] as int)
        def noms = ["n", "s", "k", "sk", "rn", "rs", "rk", "rsk"]
        noms.eachWithIndex { cleC, i -> t[cleC] = t[cleC] + (v[5 + i] as int) }
    }
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
    // Organoide entier
    pm, mp, pp, mm,
    "% " + pm, "% " + mp, "% " + pp, "% " + mm,
    m1 + "+", "% " + m1 + "+", m2 + "+", "% " + m2 + "+",
    // Dans les rosettes
    "DAPI+_dans_rosettes", "% DAPI+_dans_rosettes",
    m1 + "+_dans_rosettes", "% " + m1 + "+_dans_rosettes",
    m2 + "+_dans_rosettes", "% " + m2 + "+_dans_rosettes",
    pm + "_dans_rosettes", mp + "_dans_rosettes", pp + "_dans_rosettes", mm + "_dans_rosettes",
    "% " + pm + "_dans_rosettes", "% " + mp + "_dans_rosettes", "% " + pp + "_dans_rosettes", "% " + mm + "_dans_rosettes",
    "%DAPI+" + SLASH + m1 + "_dans_rosette",
    // Hors rosettes
    "DAPI+_hors_rosettes", "% DAPI+_hors_rosettes",
    m1 + "+_hors_rosettes", "% " + m1 + "+_hors_rosettes",
    m2 + "+_hors_rosettes", "% " + m2 + "+_hors_rosettes",
    pm + "_hors_rosettes", mp + "_hors_rosettes", pp + "_hors_rosettes", mm + "_hors_rosettes",
    "% " + pm + "_hors_rosettes", "% " + mp + "_hors_rosettes", "% " + pp + "_hors_rosettes", "% " + mm + "_hors_rosettes",
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
    // Organoide entier
    int nPM = t.s - t.sk
    int nMP = t.k - t.sk
    int nPP = t.sk
    int nMM = t.n - (t.s + t.k - t.sk)
    // Dans les rosettes
    int rPM = t.rs - t.rsk
    int rMP = t.rk - t.rsk
    int rPP = t.rsk
    int rMM = t.rn - (t.rs + t.rk - t.rsk)
    // Hors rosettes = organoide entier - rosettes
    int hn = t.n - t.rn
    int hS = t.s - t.rs
    int hK = t.k - t.rk
    int hPM = nPM - rPM
    int hMP = nMP - rMP
    int hPP = nPP - rPP
    int hMM = nMM - rMM
    def valeurs = [
        d.libelle, d.genotype, d.img, (t.num == null ? "Org ?" : "Org " + t.num), t.coupes, t.rosettes, t.n,
        // Organoide entier
        nPM, nMP, nPP, nMM,
        pct(nPM, t.n), pct(nMP, t.n), pct(nPP, t.n), pct(nMM, t.n),
        t.s, pct(t.s, t.n), t.k, pct(t.k, t.n),
        // Dans les rosettes
        t.rn, pct(t.rn, t.n),
        t.rs, pct(t.rs, t.rn),
        t.rk, pct(t.rk, t.rn),
        rPM, rMP, rPP, rMM,
        pct(rPM, t.rn), pct(rMP, t.rn), pct(rPP, t.rn), pct(rMM, t.rn),
        pct(t.rs, t.rn),
        // Hors rosettes
        hn, pct(hn, t.n),
        hS, pct(hS, hn),
        hK, pct(hK, hn),
        hPM, hMP, hPP, hMM,
        pct(hPM, hn), pct(hMP, hn), pct(hPP, hn), pct(hMM, hn),
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
    def stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date())
    def copie = new File(buildFilePath(dossier, "cellules_par_organoide_" + stamp + ".csv"))
    copie.setText(texte, "UTF-8")
    println "ATTENTION : fichier ouvert (Excel ?), résultats écrits dans " + copie.getName()
}
println "Terminé : " + (lignes.size() - 1) + " organoïdes"

