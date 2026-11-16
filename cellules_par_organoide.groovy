// ===================== RÉGLAGES =====================
String classeRosette = "rosette"   // classe des rosettes
String m1 = "SOX2"                 // 1er marqueur (tel qu'écrit dans la classe des cellules)
String m2 = "KI67"                 // 2e marqueur (Ki67 ou KI67, les deux sont acceptés)
int minCellulesCoupe = 1000        // une "coupe" avec moins de cellules = débris, ignorée
def ordreConditions = ["Ctrl-no-iMG", "C9-no-iMG", "GRN-no-iMG", "Ctrl-iMG-iso", "C9-iMG-iso", "GRN-iMG-iso"]
def lamesIgnorees = ["TRAINING", "Sparse", "SEC-"]   // lames dont le nom commence par un de ces textes : ignorees
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

// ---------- 1. Comptage par organoide (lames A + B additionnees) ----------
// Version economique en memoire : on parcourt chaque cellule une seule fois
// et on remonte la hierarchie (cellule -> rosette -> coupe) avec getParent().
def totaux = [:]
def illisibles = []
for (entry in getProject().getImageList()) {
    def nomImg = entry.getImageName()
    if (lamesIgnorees.any { nomImg.startsWith(it) }) { println "Ignoree : " + nomImg; continue }
    def hier = null
    try {
        hier = entry.readHierarchy()
    } catch (Throwable err) {
        hier = null
    }
    if (hier == null || hier.getAnnotationObjects().isEmpty()) {
        println "IMPOSSIBLE DE LIRE (lame ignoree) : " + nomImg
        illisibles << nomImg
        continue
    }
    def info = infoLame(nomImg)
    def coupes = hier.getAnnotationObjects().findAll { estCoupe(it) }
    def rosettes = hier.getAnnotationObjects().findAll { estRosette(it) && it.getROI() != null && it.getROI().isArea() }

    // Chaque rosette est rattachee a la coupe qui la contient (dans la hierarchie, sinon par sa position)
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

    // Compteurs par coupe
    def cpt = [:]
    coupes.each { c -> cpt[c] = [n: 0, s: 0, k: 0, sk: 0, rn: 0, rs: 0, rk: 0, rsk: 0, ros: 0] }
    coupeDeRosette.each { r, c -> cpt[c].ros = cpt[c].ros + 1 }

    def cacheClasse = [:]   // classe -> [SOX2+ ?, KI67+ ?] (calcule une seule fois par classe)
    for (cell in hier.getCellObjects()) {
        // Remonter : la cellule est-elle dans une rosette ? dans quelle coupe ?
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
        def t = cpt[coupe]
        def pc = cell.getPathClass()
        def pos = cacheClasse[pc]
        if (pos == null) {
            pos = [positive(cell, m1), positive(cell, m2)]
            cacheClasse[pc] = pos
        }
        boolean s = pos[0]
        boolean k = pos[1]
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

    // Ajouter chaque coupe au total de son organoide
    int nCoupes = 0
    int nRosettes = 0
    cpt.each { coupe, c ->
        if (c.n < minCellulesCoupe) return
        def num = numeroOrg(coupe)
        def cle = info.groupe + "|" + num
        if (!totaux.containsKey(cle))
            totaux[cle] = [cond: info.condition, num: num, coupes: 0, rosettes: 0,
                           n: 0, s: 0, k: 0, sk: 0, rn: 0, rs: 0, rk: 0, rsk: 0]
        def t = totaux[cle]
        t.coupes = t.coupes + 1
        t.rosettes = t.rosettes + c.ros
        ["n", "s", "k", "sk", "rn", "rs", "rk", "rsk"].each { cleC -> t[cleC] = t[cleC] + c[cleC] }
        nCoupes++
        nRosettes += c.ros
    }
    println "OK : " + nomImg + " (" + nCoupes + " coupes, " + nRosettes + " rosettes sur " + rosettes.size() + " dans la lame)"
    hier = null
    cpt = null
    System.gc()
}

// ---------- 2. Tableau par organoïde ----------
// Noms des categories
String pm = m1 + "+_" + m2 + "-"   // SOX2+_KI67-
String mp = m2 + "+_" + m1 + "-"   // KI67+_SOX2-
String pp = m1 + "+_" + m2 + "+"   // SOX2+_KI67+
String mm = m1 + "-_" + m2 + "-"   // SOX2-_KI67-
String SLASH = String.valueOf((char) 47)   // caractere "barre oblique"

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

// Une ligne = liste de [nom de colonne, valeur] : chaque nombre est suivi de son pourcentage
def construireLigne = { t ->
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
    def c = []
    // ----- Identification
    c << ["Condition", d.libelle]
    c << ["Genotype", d.genotype]
    c << ["iMG", d.img]
    c << ["Organoide", (t.num == null ? "Org ?" : "Org " + t.num)]
    c << ["Nb_coupes", t.coupes]
    c << ["Nb_rosettes", t.rosettes]
    c << ["DAPI+", t.n]
    // ----- Organoide entier (% sur les DAPI+ de l'organoide)
    c << [m1 + "+", t.s];  c << ["% " + m1 + "+", pct(t.s, t.n)]
    c << [m2 + "+", t.k];  c << ["% " + m2 + "+", pct(t.k, t.n)]
    c << [pm, nPM];        c << ["% " + pm, pct(nPM, t.n)]
    c << [mp, nMP];        c << ["% " + mp, pct(nMP, t.n)]
    c << [pp, nPP];        c << ["% " + pp, pct(nPP, t.n)]
    c << [mm, nMM];        c << ["% " + mm, pct(nMM, t.n)]
    // ----- Dans les rosettes (% sur les DAPI+ des rosettes)
    c << ["DAPI+_dans_rosettes", t.rn];           c << ["% DAPI+_dans_rosettes", pct(t.rn, t.n)]
    c << [m1 + "+_dans_rosettes", t.rs];          c << ["% " + m1 + "+_dans_rosettes", pct(t.rs, t.rn)]
    c << [m2 + "+_dans_rosettes", t.rk];          c << ["% " + m2 + "+_dans_rosettes", pct(t.rk, t.rn)]
    c << [pm + "_dans_rosettes", rPM];            c << ["% " + pm + "_dans_rosettes", pct(rPM, t.rn)]
    c << [mp + "_dans_rosettes", rMP];            c << ["% " + mp + "_dans_rosettes", pct(rMP, t.rn)]
    c << [pp + "_dans_rosettes", rPP];            c << ["% " + pp + "_dans_rosettes", pct(rPP, t.rn)]
    c << [mm + "_dans_rosettes", rMM];            c << ["% " + mm + "_dans_rosettes", pct(rMM, t.rn)]
    c << ["%DAPI+" + SLASH + m1 + "_dans_rosette", pct(t.rs, t.rn)]
    c << ["% " + m1 + "+_dans_rosettes" + SLASH + m1 + "+_organoide", pct(t.rs, t.s)]
    c << ["% " + m2 + "+_dans_rosettes" + SLASH + m2 + "+_organoide", pct(t.rk, t.k)]
    // ----- Hors rosettes (% sur les DAPI+ hors rosettes)
    c << ["DAPI+_hors_rosettes", hn];             c << ["% DAPI+_hors_rosettes", pct(hn, t.n)]
    c << [m1 + "+_hors_rosettes", hS];            c << ["% " + m1 + "+_hors_rosettes", pct(hS, hn)]
    c << [m2 + "+_hors_rosettes", hK];            c << ["% " + m2 + "+_hors_rosettes", pct(hK, hn)]
    c << [pm + "_hors_rosettes", hPM];            c << ["% " + pm + "_hors_rosettes", pct(hPM, hn)]
    c << [mp + "_hors_rosettes", hMP];            c << ["% " + mp + "_hors_rosettes", pct(hMP, hn)]
    c << [pp + "_hors_rosettes", hPP];            c << ["% " + pp + "_hors_rosettes", pct(hPP, hn)]
    c << [mm + "_hors_rosettes", hMM];            c << ["% " + mm + "_hors_rosettes", pct(hMM, hn)]
    c << ["%DAPI+" + SLASH + m1 + "_hors_rosette", pct(hS, hn)]
    c << ["% " + m1 + "+_hors_rosettes" + SLASH + m1 + "+_organoide", pct(hS, t.s)]
    c << ["% " + m2 + "+_hors_rosettes" + SLASH + m2 + "+_organoide", pct(hK, t.k)]
    return c
}

def lignesOrg = liste.collect { construireLigne(it) }
def lignes = []
if (!lignesOrg.isEmpty()) lignes << lignesOrg[0].collect { it[0] }.join(";")
lignesOrg.each { l -> lignes << l.collect { it[1] }.join(";") }

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
if (!illisibles.isEmpty()) {
    println "ATTENTION : " + illisibles.size() + " lame(s) ignoree(s) car leur fichier de donnees est illisible :"
    illisibles.each { println "   - " + it }
}
