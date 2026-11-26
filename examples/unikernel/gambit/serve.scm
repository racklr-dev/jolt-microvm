;; serve.scm — separate module linked AFTER the full jolt boot (boot-probe.c).
;; A COM1 nREPL endpoint: the read-eval core an nREPL unikernel needs.
;; Reads bencode-encoded nREPL messages from COM1 (libkrun wires it to host
;; stdin), evaluates them with the boot's live compiler, and writes bencode
;; responses. Stdout produced by the evaluated code is captured and sent in the
;; "out" slot before the value, exactly like repl-main.ss's repl-step.
;;
;; Preamble (ASCII, before the bencode loop):
;;   SERVE: jolt COM1 nREPL endpoint
;;   eval> ... => ...    compiler self-check
;;   READY               handshake — host may start bencode after this line
;;
;; Wire: pure bencode. Each request/response is one bencode dict with string
;; keys, emitted in bencode-sorted order. Response keys (sorted):
;;   [err] id ns op [out] session status value
;;
;; Sessions: clone returns a fresh session id; each session tracks its own ns
;; (default "user"). close drops the session. describe advertises the ops map
;; so a real nREPL client (cider/lein/calva) can negotiate.
;;
;; Byte length in the bencode string prefix counts characters (ASCII); the
;; transport is ASCII-only for now — UTF-8 payloads need a utf8 byte count.
(c-declare "extern int bare_com1_try_getc(void); extern int bare_com1_rx_ready(void);")

(define com1-try-getc (c-lambda () int "bare_com1_try_getc"))
(define com1-out (current-output-port))
(define *session-counter* 0)
(define *sessions* (make-table test: string=?))
(table-set! *sessions* "0" "user")

(define (fresh-session-id)
  (set! *session-counter* (+ *session-counter* 1))
  (number->string *session-counter*))

(define (session-ns id)
  (let ((ns (table-ref *sessions* id #f)))
    (or ns "user")))

(define (com1-getc)
  (let loop ()
    (let ((c (com1-try-getc)))
      (if (= c -1) (loop) c))))

(define (put-c c) (display (integer->char c) com1-out))
(define (put-s s) (display s com1-out))
(define (put-line . xs)
  (for-each (lambda (x) (display x com1-out)) xs)
  (newline com1-out))

;; --- error rendering (same as repl-main.ss) ------------------------------
(define (throw-text e)
  (or (guard (ee (#t #f))
        (let ((v (jolt-unwrap-throw e)))
          (and (jolt-ex-info-record? v)
               (string-append (jolt-ex-info-record-class-name v) ": "
                              (let ((m (jolt-ex-info-record-message v)))
                                (if (string? m) m (jolt-pr-readable m)))))))
      (guard (ee (#t #f)) (and (gambit-host-error? e) (gambit-error-tostring e)))
      (guard (ee (#t #f)) (let ((v (jolt-unwrap-throw e))) (jolt-pr-readable v)))
      "error"))

;; An expression's printed output belongs to the terminal that ran it, so each
;; evaluation runs with stdout captured; the text is emitted in "out" before the
;; value (or before the error, if it printed and then threw).
(define (eval-step code ns)
  (let ((printed "") (value #f) (failure #f))
    (set! printed
      (call-with-output-string
        (lambda (port)
          (parameterize ((current-output-port port))
            (guard (e (#t (set! failure (throw-text e))))
              (set! value (jolt-pr-readable (jolt-compile-eval code ns))))))))
    (list value printed failure)))

;; --- bencode writer ------------------------------------------------------
(define (bw-int n) (put-c 105) (display n com1-out) (put-c 101))          ; i..e
(define (bw-str s) (display (string-length s) com1-out) (put-c 58) (display s com1-out))

;; response dict from an alist of (key . value) string pairs given in
;; already-sorted key order, plus a list of status strings.
(define (bw-resp pairs statuses)
  (put-c 100)                                          ; d
  (for-each (lambda (p) (bw-str (car p)) (bw-str (cdr p))) pairs)
  (bw-str "status") (put-c 108) (for-each bw-str statuses) (put-c 101)   ; l..e
  (put-c 101))                                         ; e

;; --- bencode reader ------------------------------------------------------
(define (br-any)
  (let ((c (com1-getc)))
    (cond
      ((= c 105) (br-int))                 ; i
      ((= c 108) (br-list))                ; l
      ((= c 100) (br-dict))                ; d
      ((and (>= c 48) (<= c 57)) (br-str c))
      ((= c 101) #f)                       ; e (end marker)
      (else (error "bencode: bad byte" c)))))

(define (br-int)
  (let loop ((neg #f) (acc 0) (c (com1-getc)))
    (cond
      ((= c 45) (loop #t acc (com1-getc)))
      ((= c 101) (if neg (- acc) acc))
      ((and (>= c 48) (<= c 57)) (loop neg (+ (* acc 10) (- c 48)) (com1-getc)))
      (else (error "bencode: bad int byte" c)))))

(define (br-digits c acc)
  (if (= c 58) acc
      (br-digits (com1-getc) (+ (* acc 10) (- c 48)))))

(define (br-str first-digit)
  (let* ((len (br-digits first-digit 0))
         (s (make-string len)))
    (let loop ((i 0))
      (if (< i len)
          (begin
            (string-set! s i (integer->char (com1-getc)))
            (loop (+ i 1)))
          s))))

(define (br-list)
  (let loop ((acc '()))
    (let ((v (br-any)))
      (if (eq? v #f) (reverse acc)
          (loop (cons v acc))))))

(define (br-dict)
  (let loop ((acc '()))
    (let ((k (br-any)))
      (if (eq? k #f) (reverse acc)
          (let ((v (br-any)))
            (loop (cons (cons k v) acc)))))))

(define (req-get req key)
  (let ((p (assoc key req)))
    (and p (cdr p))))

;; --- nREPL handling ------------------------------------------------------
;; pairs alist can carry #f placeholders for optional slots; emit only real pairs.
(define (bw-pairs pairs)
  (for-each (lambda (p) (if p (begin (bw-str (car p)) (bw-str (cdr p))))) pairs))

(define (bw-resp2 pairs statuses)
  (put-c 100)
  (bw-pairs pairs)
  (bw-str "status") (put-c 108) (for-each bw-str statuses) (put-c 101)
  (put-c 101))

(define (eval-resp id session ns code)
  (let* ((r (eval-step code ns))
         (value (car r)) (out (cadr r)) (err (caddr r))
         (pairs (list (if err (cons "err" err) #f)
                      (cons "id" id)
                      (cons "ns" ns)
                      (cons "op" "eval")
                      (if (> (string-length out) 0) (cons "out" out) #f)
                      (cons "session" session)
                      (cons "value" (if err "" value)))))
    (bw-resp2 pairs (if err '("eval-error" "done") '("done")))))

(define (clone-resp id new-session)
  (bw-resp2 (list (cons "id" id)
                  (cons "new-session" new-session)
                  (cons "session" new-session))
            '("done")))

(define (describe-resp id session)
  (put-c 100)
  (bw-str "id") (bw-str id)
  (bw-str "ops")
  (put-c 100)
  (bw-str "clone") (put-c 100) (put-c 101)
  (bw-str "close") (put-c 100) (put-c 101)
  (bw-str "describe") (put-c 100) (put-c 101)
  (bw-str "eval") (put-c 100) (put-c 101)
  (put-c 101)
  (bw-str "session") (bw-str session)
  (bw-str "status") (put-c 108) (bw-str "done") (put-c 101)
  (bw-str "versions")
  (put-c 100)
  (bw-str "jolt") (put-c 100) (bw-str "version-string") (bw-str "0.1.0") (put-c 101)
  (bw-str "nrepl") (put-c 100) (bw-str "version-string") (bw-str "0.9.0") (put-c 101)
  (put-c 101)
  (put-c 101))

(define (close-resp id session)
  (bw-resp2 (list (cons "id" id)
                  (cons "session" session))
            '("done" "session-closed")))

(define (handle req)
  (let* ((op      (req-get req "op"))
         (id      (or (req-get req "id") ""))
         (session (or (req-get req "session") "0"))
         (code    (or (req-get req "code") "")))
    (cond
      ((string=? op "eval")
       (eval-resp id session (session-ns session) code))
      ((string=? op "clone")
       (let ((ns (session-ns session)) (new (fresh-session-id)))
         (table-set! *sessions* new ns)
         (clone-resp id new)))
      ((string=? op "describe")
       (describe-resp id session))
      ((string=? op "close")
       (table-set! *sessions* session #f)
       (close-resp id session))
      (else
       (bw-resp2 (list (cons "id" id)
                       (cons "session" session))
                 '("done"))))))

(define (smoke src)
  (put-s "eval> ") (put-s src) (put-s " => ")
  (guard (e (#t (put-s "THREW")))
    (put-s (jolt-pr-readable (jolt-compile-eval src "user"))))
  (newline com1-out))

(define (serve)
  (put-line "SERVE: jolt COM1 nREPL endpoint")
  ;; self-check: the compiler must be live in this module chain before the loop.
  (smoke "(+ 1 2)")
  (smoke "{:a 1 :b 2}")
  (smoke "(map inc [1 2 3])")
  (put-line "READY")
  (let loop ()
    (let ((req (br-any)))
      (if req (handle req)))
    (loop)))

(serve)
