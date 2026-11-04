;nyquist plug-in
;version 4
;type process
;name "Parrot Voice Tone (PSVC)"
;action "Applying speech-preserving parrot tone..."
;control VOICE_MIX "Human / parrot blend" int "%" 50 0 100
;control NASAL "Nasal resonance" int "%" 45 0 100
;control BRIGHTNESS "Bright beak edge" int "%" 25 0 100
;control ROUGHNESS "Roughness / grain" int "%" 12 0 100
;control FLUTTER "Vocal flutter" int "%" 8 0 100

; This effect keeps the spoken waveform as its dry reference and adds a
; controllable, bird-like spectral character on a parallel wet path.
; It is a lightweight DSP approximation, not a parrot speech synthesizer.
; The controls and defaults correspond to config/parrot-svc/voice-filters.properties.

(defun psvc-parrot-channel (sig)
  (let* ((dry sig)
         (wet (highpass2 (lowpass2 sig 7200.0 0.707) 150.0 0.707))
         (nasal-amount (/ NASAL 100.0))
         (bright-amount (/ BRIGHTNESS 100.0))
         (rough-amount (/ ROUGHNESS 100.0))
         (flutter-amount (/ FLUTTER 100.0))
         (wet (eq-band wet 620.0 (* -0.8 nasal-amount) 1.1))
         (wet (eq-band wet 1350.0 (* 3.0 nasal-amount) 1.1))
         (wet (eq-band wet 2450.0 (* 3.5 nasal-amount) 1.0))
         (wet (eq-band wet 3800.0 (* 3.0 bright-amount) 0.9))
         (levels (round (- 2048.0 (* 1536.0 rough-amount))))
         (rough-blend (* 0.16 rough-amount))
         (quantized (quantize wet levels))
         (wet (sum (mult (- 1.0 rough-blend) wet)
                   (mult rough-blend quantized)))
         (flutter-depth (* 0.035 flutter-amount))
         (flutter-envelope (sum 1.0
                                (mult flutter-depth
                                      (osc (hz-to-step 5.5) 1.0))))
         (wet (mult wet flutter-envelope))
         (blend (/ VOICE_MIX 100.0)))
    (sum (mult (- 1.0 blend) dry)
         (mult blend wet))))

;; Audacity may pass mono as one SOUND or stereo as a two-channel array.
(if (arrayp s)
    (vector (psvc-parrot-channel (aref s 0))
            (psvc-parrot-channel (aref s 1)))
    (psvc-parrot-channel s))
