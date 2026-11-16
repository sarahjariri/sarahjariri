// Diagnostic : quelles lames du projet ont un fichier de donnees (data.qpdata) illisible ?
// A lancer avec Run (pas Run for project), aucune image ouverte.
getProject().getImageList().each { e ->
    def dossier = e.getEntryPath().toFile()
    def f = new File(dossier, "data.qpdata")
    def b = new File(dossier, "data.qpdata.backup")
    String etat
    try {
        def h = e.readHierarchy()
        etat = (h == null) ? "ILLISIBLE" : "OK (" + h.getAllObjects(false).size() + " objets)"
    } catch (Throwable t) {
        etat = "ILLISIBLE : " + t.getMessage()
    }
    String tailleF = f.exists() ? (f.length().intdiv(1024 * 1024) + " Mo") : "absent"
    String tailleB = b.exists() ? (b.length().intdiv(1024 * 1024) + " Mo") : "absent"
    println etat + "  |  " + e.getImageName()
    println "      data.qpdata : " + tailleF + "  |  data.qpdata.backup : " + tailleB
    println "      dossier : " + dossier
}
println "Diagnostic termine"
