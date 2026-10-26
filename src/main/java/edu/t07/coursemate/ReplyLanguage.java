package edu.t07.coursemate;

/** Language for the tool window and for the next model answer. */
public enum ReplyLanguage {
    ZH,
    EN;

    public String code() {
        return this == EN ? "en" : "zh";
    }

    public String text(String chinese, String english) {
        return this == EN ? english : chinese;
    }
}
