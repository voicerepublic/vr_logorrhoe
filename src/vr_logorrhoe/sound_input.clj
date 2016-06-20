;; Well written tutorial on the Java Sampled Package:
;; http://docs.oracle.com/javase/tutorial/sound/sampled-overview.html

(ns vr-logorrhoe.sound-input
  (:require [clojure.java.io :as io]
            [vr-logorrhoe
             [config :as config]
             [encoder :refer [encode]]
             [shout :as shout]
             [utils :as utils :refer [log]]])
  (:import [java.io PipedInputStream PipedOutputStream]
           java.lang.Thread
           java.nio.ByteBuffer
           [javax.sound.sampled AudioFormat AudioSystem DataLine$Info LineListener TargetDataLine]))

(defn- get-mixer-info []
  "Retrieve supported mixers from OS"
  (seq (. AudioSystem (getMixerInfo))))

(defn- get-mixer-infos []
  "Returns mixer-info, name, description of each mixer"
  (map #(let [m %] {:mixer-info m
                    :name (. m (getName))
                    :description (. m (getDescription))})
       (get-mixer-info)))

;; This method is called by the GUI to query for available mixers
(defn get-mixer-names []
  "Returns all available Mixers by name [String]"
  (map :name (get-mixer-infos)))

(defn- get-recorder-mixer-info [recorder-name]
  "Returns Mixer Info for a specific recorder, queried by name [String]"
  (:mixer-info (first (filter #(= recorder-name (:name %)) (get-mixer-infos)))))

(def recorder-mixer-info
  (get-recorder-mixer-info (:recording-device @config/settings)))

;; Get the recorder mixer
(def recorder-mixer (. AudioSystem (getMixer recorder-mixer-info)))

;; Create a RAW data format. It can be played like this:
;;   aplay -t raw clojure.wav -c 1 -r 44100 -f S16_LE
;; -> float sampleRate, int sampleSizeInBits, int channels, boolean signed, boolean bigEndian
(def audio-format (new AudioFormat (utils/parse-int (:sample-freq @config/settings))
                       (utils/parse-int (:sample-size @config/settings))
                       (utils/parse-int (:audio-channels @config/settings))
                       true false))

;; Get supported target line info
(def target-line-info (new DataLine$Info TargetDataLine audio-format))

(defn- get-recorder-line []
  "Sets up a recorder line from the mixer and attaches event
  listeners"

  ;; Get a target line from the ixer
  (let [recorder-line (try
                        (. recorder-mixer
                           (getLine target-line-info))
                        (catch Exception e
                          (log "Exception with getting the line for mixer: " e)
                          false))]

    ;; Add a line listener for events on the line. This is an optional step
    ;; and is currently only used for logging purposes.
    (. recorder-line (addLineListener
                      (reify LineListener
                        (update [this evt]
                          (do (print "Event: " (. evt (getType)))
                              (newline)
                              (. *out* (flush)))))))

    recorder-line))

(defn- write-buffer-to-file [buffer file]
  "Takes a ByteBuffer and writes it into a FileChannel"
  (future
    (.write file buffer)))

(defn- record [recorder-line mic-buffer-size]
  "Starts collecting audio samples from it, encodes it with `lame`,
  writes a raw and a mp3 file and streams the mp3 audio samples via
  HTTP PUT."

  (log "Entering `record` function")

  (swap! config/app-state assoc :audio-samples-count 0)

  (let [raw-file (.getChannel (java.io.FileOutputStream. (utils/conj-path (:backup-folder @config/settings) "tmp.wav")))
        audio-input-stream (new PipedInputStream)
        audio-output-stream (PipedOutputStream. audio-input-stream)]

    (while (:recording @config/app-state)
      (let [mic-sample-buffer    (make-array (. Byte TYPE) mic-buffer-size)
            ;; Only required for side-effect
            mic-sample-count (. recorder-line (read mic-sample-buffer 0 mic-buffer-size))
            ;; Current sample
            mic-sample-bbyte (. ByteBuffer (wrap mic-sample-buffer))]

        (swap! config/app-state update :audio-samples-count inc)

        (future
          (.write audio-output-stream mic-sample-buffer 0 mic-sample-count))

        ;; Successively write sample after sample in raw format
        (write-buffer-to-file mic-sample-bbyte raw-file)

        ;; Give the audio buffer a little heads-up before starting to
        ;; encode and stream. Otherwise the buffer will be depleted
        ;; quickly and the encoding/streaming process will terminate!
        (if (= (:audio-samples-count @config/app-state) 9)
          (future
            (log "Start encoding!")
            ;; TODO: Duplicate input-stream so that it can be shouted
            ;; and persisted locally.
            (encode audio-input-stream #(;(io/copy % (io/file "tmp.mp3"))
                                         (shout/stream %))))))

      ;; TODO: Call the `drain` method to drain the recorder-line when
      ;; the recording stops. Otherwise the recorded data might seem
      ;; to end pre-maturely.
      (. Thread (sleep 20)))


    ;; stop reading from the input line
    (. recorder-line (stop))

    ;; close recorder
    (. recorder-line (close))

    (.close raw-file)))

(defn start-recording []
  "Opens the specified Microphone port and starts the actual recording."

  (log "start-recording")

  (future
    (let [recorder-line (get-recorder-line)
          ;; The size of the buffer is deliberately 1/5th of the lines
          ;; buffer. Otherwise there's a racing condition between the mixer
          ;; writing to and this code reading from the buffer.
          ;; This needs to be called before the line is open.
          mic-buffer-size (int (/ (.getBufferSize recorder-line) 5))]

      (swap! config/app-state assoc :recording true)

      ;; Open the Port
      (. recorder-line (open audio-format mic-buffer-size))

      ;; Start Audio Capture
      (. recorder-line (start))

      (record recorder-line mic-buffer-size))))

(defn stop-recording []
  "Sets a magic-var so that the `record` function knows to end the
  recording."

  (log "stop-recording")
  (swap! config/app-state assoc :recording false))



(comment
  (try
    (start-recording)
    (stop-recording)
    (catch Exception e
      (println "Caught: " e)
      (. recorder-line (stop))
      (. recorder-line (close))
      ))
  )
