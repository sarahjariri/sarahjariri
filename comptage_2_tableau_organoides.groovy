// ===================== ETAPE 2 / 2 : TABLEAU PAR ORGANOIDE =====================
// A lancer avec  Run  (une seule fois), APRES l'etape 1
// Lit les fichiers de resultats/par_lame/ et cree resultats/cellules_par_organoide.csv
// ==============================================================================
String m1 = "SOX2"
String m2 = "KI67"
int minCellulesCoupe = 1000        // une "coupe" avec moins de cellules = debris, ignoree
def ordreConditions = ["Ctrl-no-iMG", "C9-no-iMG", "GRN-no-iMG", "Ctrl-iMG-iso", "C9-iMG-iso", "GRN-iMG-iso"]
// ==============================================================================

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

def pct = { int a, int b -> String.format(Locale.FRANCE, "%.2f", b > 0 ? 100.0 * a / b : 0.0) }

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
    println "Lu : " + f.getName()
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

