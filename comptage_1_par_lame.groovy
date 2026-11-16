// ===================== ETAPE 1 / 2 : COMPTAGE PAR LAME =====================
// A lancer avec  Run > Run for project (without save)
// Ecrit un petit fichier par lame dans resultats/par_lame/
// ==========================================================================
String classeRosette = "rosette"   // classe des rosettes
String m1 = "SOX2"                 // 1er marqueur (tel qu'ecrit dans la classe des cellules)
String m2 = "KI67"                 // 2e marqueur (Ki67 ou KI67, les deux sont acceptes)
def lamesIgnorees = ["TRAINING", "Sparse", "SEC-"]   // lames ignorees (debut du nom)
def ordreConditions = []           // (non utilise ici)
// ==========================================================================

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
def lignes = ["Image;Groupe;Condition;Organoide;Rosettes;n;s;k;sk;rn;rs;rk;rsk"]
cpt.each { coupe, c ->
    def num = numeroOrg(coupe)
    lignes << [nomImg, info.groupe, info.condition, (num == null ? "" : num), c.ros,
               c.n, c.s, c.k, c.sk, c.rn, c.rs, c.rk, c.rsk].join(";")
}
def dossier = buildFilePath(PROJECT_BASE_DIR, "resultats", "par_lame")
mkdirs(dossier)
def nomFichier = nomImg.replaceAll('[^A-Za-z0-9_-]', '_') + ".csv"
new File(buildFilePath(dossier, nomFichier)).setText(lignes.join("\n"), "UTF-8")
println "OK : " + nomImg + " (" + coupes.size() + " coupes, " + rosettes.size() + " rosettes, " + hier.getCellObjects().size() + " cellules)"
