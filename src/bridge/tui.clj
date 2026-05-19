(ns bridge.tui
  (:require [bridge.next :as next-guide]
            [clojure.string :as str])
  (:import [org.jline.reader EndOfFileException LineReaderBuilder UserInterruptException]
           [org.jline.terminal TerminalBuilder]))

(defn- clear-screen []
  (str "\u001b[?1049h\u001b[H\u001b[2J"))

(defn- restore-screen []
  "\u001b[?1049l")

(defn- section-lines [title rows]
  (when (seq rows)
    (concat [(str title ":")]
            rows
            [""])))

(defn- obligation-line [marker obligation]
  (str "  " marker " " (:summary obligation)
       (when-let [reason (:reason obligation)]
         (str " (" reason ")"))))

(defn- command-lines [obligation]
  (mapcat (fn [{:keys [id command]}]
            [(str "      " id ": " command)])
          (:commands obligation)))

(defn- obligation-lines [marker obligations]
  (mapcat (fn [obligation]
            (cons (obligation-line marker obligation)
                  (command-lines obligation)))
          obligations))

(defn- view-lines [status]
  (let [intent (:intent status)
        subsystems (get-in intent [:semantic-scope :subsystems])]
    (vec
     (concat
      [(if (= "clear" (:status status))
         "Bridge Status: All Clear"
         "Bridge Status: Attention Required")
       "------------------------------------------------------------------"
       (str "Project: " (:project status))
       (str "Changed files: " (if (seq (:changed-files status))
                                (str/join ", " (:changed-files status))
                                "none detected"))]
      (when (seq subsystems)
        [(str "Subsystems: " (str/join ", " subsystems))])
      (when-let [risk (:risk-class intent)]
        [(str "Risk Class: " risk)])
      [""]
      (section-lines "Failed Obligations" (obligation-lines "[!]" (:failed-obligations status)))
      (section-lines "Pending Obligations" (obligation-lines "[ ]" (:open-obligations status)))
      (section-lines "Stale Artifacts"
                     (map (fn [{:keys [path kind status stale-because]}]
                            (str "  [ ] " (or path kind)
                                 (when status (str " (" status ")"))
                                 (when (seq stale-because)
                                   (str " because of " (str/join ", " stale-because)))))
                          (:stale-artifacts status)))
      (section-lines "Existing Workflow Problems"
                     (map (fn [{:keys [subject workflow-state open-obligation-count failed-obligation-count]}]
                            (str "  [!] " subject
                                 " state=" workflow-state
                                 " open=" open-obligation-count
                                 " failed=" failed-obligation-count))
                          (:subject-problems status)))
      (section-lines "Completed Obligations" (obligation-lines "[x]" (:completed-obligations status)))
      (section-lines "Completed Evidence"
                     (map (fn [{:keys [id kind subject finished-at]}]
                            (str "  [x] " (or id kind)
                                 (when subject (str " for " subject))
                                 (when finished-at (str " at " finished-at))))
                          (:completed-evidence status)))
      (when (= "clear" (:status status))
        ["No pending obligations, stale artifacts, or regressions in tracked subsystems/artifact subjects were found." ""])
      ["q quits. This Phase 1 view is read-only."]))))

(defn render-status! [status]
  (let [terminal (-> (TerminalBuilder/builder) (.build))
        reader (-> (LineReaderBuilder/builder)
                   (.terminal terminal)
                   (.build))]
    (try
      (print (clear-screen))
      (println (str/join "\n" (view-lines status)))
      (flush)
      (loop []
        (let [line (try
                     (.readLine reader "bridge> ")
                     (catch EndOfFileException _ "q")
                     (catch UserInterruptException _ "q"))]
          (when-not (#{"q" "quit" "exit"} (str/lower-case (str/trim (or line ""))))
            (println "Read-only in Phase 1. Press q to quit.")
            (recur))))
      (finally
        (print (restore-screen))
        (flush)
        (.close terminal)))))
