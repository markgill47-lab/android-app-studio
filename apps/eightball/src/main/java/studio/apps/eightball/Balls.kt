package studio.apps.eightball

// The three balls. Each line break is where the text wraps on the triangle,
// short lines toward the narrow apex. A list shorter than the die's 20 faces
// repeats around it.
enum class Ball(val face: Int, val edge: Int, val answers: List<String>) {
    // The 20 classic answers: 10 yes, 5 maybe, 5 no.
    BLUE(
        face = 0xFF2B3FC2.toInt(),
        edge = 0xFF18247A.toInt(),
        answers = listOf(
            "IT IS\nCERTAIN",
            "IT IS\nDECIDEDLY\nSO",
            "WITHOUT\nA DOUBT",
            "YES\nDEFINITELY",
            "YOU\nMAY RELY\nON IT",
            "AS I\nSEE IT,\nYES",
            "MOST\nLIKELY",
            "OUTLOOK\nGOOD",
            "YES",
            "SIGNS\nPOINT\nTO YES",
            "REPLY\nHAZY,\nTRY AGAIN",
            "ASK\nAGAIN\nLATER",
            "BETTER\nNOT TELL\nYOU NOW",
            "CANNOT\nPREDICT\nNOW",
            "CONCEN-\nTRATE AND\nASK AGAIN",
            "DON'T\nCOUNT\nON IT",
            "MY\nREPLY\nIS NO",
            "MY\nSOURCES\nSAY NO",
            "OUTLOOK\nNOT SO\nGOOD",
            "VERY\nDOUBTFUL",
        ),
    ),

    // Annoyance, aimed at nobody in particular.
    RED(
        face = 0xFFB3202A.toInt(),
        edge = 0xFF6E1016.toInt(),
        answers = listOf(
            "FUCK\nTHIS\nNOISE",
            "YOU GOTTA\nBE FUCKIN'\nKIDDING ME",
            "THIS IS\nTOTAL\nBULLSHIT",
            "OH, FOR\nFUCK'S\nSAKE",
            "ABSO-\nLUTELY THE\nFUCK NOT",
            "WHAT\nFRESH HELL\nIS THIS",
            "WELL,\nSHIT",
            "FUCKITY\nFUCK\nFUCK",
            "CLUSTER-\nFUCK\nCONFIRMED",
            "HELL\nTO THE\nNO",
            "SHIT\nON A\nSTICK",
            "NOT\nTODAY,\nSATAN",
            "NOPE.\nFUCK\nTHAT.",
            "THIS IS A\nDUMPSTER\nFIRE",
        ),
    ),

    // Dinner: dishes, proteins and broad categories.
    GREEN(
        face = 0xFF1F8A3E.toInt(),
        edge = 0xFF0F4D21.toInt(),
        answers = listOf(
            "POLLO\nDE TINGA",
            "POPPY-\nSEED\nCHICKEN",
            "TACOS",
            "BURGERS",
            "BEEF",
            "CHICKEN",
            "FISH",
            "SOUP",
            "PIZZA",
            "CHINESE",
            "PASTA",
            "STIR\nFRY",
            "CHILI",
            "BREAK-\nFAST FOR\nDINNER",
            "PORK",
            "SHRIMP",
            "THAI",
            "SALAD",
            "INDIAN",
            "LEFT-\nOVERS",
        ),
    ),
}
