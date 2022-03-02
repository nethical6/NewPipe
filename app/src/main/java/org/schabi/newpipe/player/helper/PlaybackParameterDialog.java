package org.schabi.newpipe.player.helper;

import static org.schabi.newpipe.player.Player.DEBUG;
import static org.schabi.newpipe.util.Localization.assureCorrectAppLanguage;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.DialogPlaybackParameterBinding;
import org.schabi.newpipe.util.SliderStrategy;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;

import icepick.Icepick;
import icepick.State;

public class PlaybackParameterDialog extends DialogFragment {
    private static final String TAG = "PlaybackParameterDialog";

    // Minimum allowable range in ExoPlayer
    private static final double MIN_PLAYBACK_VALUE = 0.10f;
    private static final double MAX_PLAYBACK_VALUE = 3.00f;

    private static final double STEP_1_PERCENT_VALUE = 0.01f;
    private static final double STEP_5_PERCENT_VALUE = 0.05f;
    private static final double STEP_10_PERCENT_VALUE = 0.10f;
    private static final double STEP_25_PERCENT_VALUE = 0.25f;
    private static final double STEP_100_PERCENT_VALUE = 1.00f;

    private static final double DEFAULT_TEMPO = 1.00f;
    private static final double DEFAULT_PITCH_PERCENT = 1.00f;
    private static final double DEFAULT_STEP = STEP_25_PERCENT_VALUE;
    private static final boolean DEFAULT_SKIP_SILENCE = false;

    private static final SliderStrategy QUADRATIC_STRATEGY = new SliderStrategy.Quadratic(
            MIN_PLAYBACK_VALUE,
            MAX_PLAYBACK_VALUE,
            1.00f,
            10_000);

    private static final SliderStrategy SEMITONE_STRATEGY = new SliderStrategy() {
        @Override
        public int progressOf(final double value) {
            return PlayerSemitoneHelper.percentToSemitones(value) + 12;
        }

        @Override
        public double valueOf(final int progress) {
            return PlayerSemitoneHelper.semitonesToPercent(progress - 12);
        }
    };

    @Nullable
    private Callback callback;

    @State
    double initialTempo = DEFAULT_TEMPO;
    @State
    double initialPitchPercent = DEFAULT_PITCH_PERCENT;
    @State
    boolean initialSkipSilence = DEFAULT_SKIP_SILENCE;

    @State
    double tempo = DEFAULT_TEMPO;
    @State
    double pitchPercent = DEFAULT_PITCH_PERCENT;
    @State
    double stepSize = DEFAULT_STEP;
    @State
    boolean skipSilence = DEFAULT_SKIP_SILENCE;

    private DialogPlaybackParameterBinding binding;

    public static PlaybackParameterDialog newInstance(
            final double playbackTempo,
            final double playbackPitch,
            final boolean playbackSkipSilence,
            final Callback callback
    ) {
        final PlaybackParameterDialog dialog = new PlaybackParameterDialog();
        dialog.callback = callback;

        dialog.initialTempo = playbackTempo;
        dialog.initialPitchPercent = playbackPitch;
        dialog.initialSkipSilence = playbackSkipSilence;

        dialog.tempo = dialog.initialTempo;
        dialog.pitchPercent = dialog.initialPitchPercent;
        dialog.skipSilence = dialog.initialSkipSilence;

        return dialog;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Lifecycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onAttach(@NonNull final Context context) {
        super.onAttach(context);
        if (context instanceof Callback) {
            callback = (Callback) context;
        } else if (callback == null) {
            dismiss();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        Icepick.saveInstanceState(this, outState);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Dialog
    //////////////////////////////////////////////////////////////////////////*/

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable final Bundle savedInstanceState) {
        assureCorrectAppLanguage(getContext());
        Icepick.restoreInstanceState(this, savedInstanceState);

        binding = DialogPlaybackParameterBinding.inflate(LayoutInflater.from(getContext()));
        initUI();

        final AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(requireActivity())
                .setView(binding.getRoot())
                .setCancelable(true)
                .setNegativeButton(R.string.cancel, (dialogInterface, i) -> {
                    setAndUpdateTempo(initialTempo);
                    setAndUpdatePitch(initialPitchPercent);
                    setAndUpdateSkipSilence(initialSkipSilence);
                    updateCallback();
                })
                .setNeutralButton(R.string.playback_reset, (dialogInterface, i) -> {
                    setAndUpdateTempo(DEFAULT_TEMPO);
                    setAndUpdatePitch(DEFAULT_PITCH_PERCENT);
                    setAndUpdateSkipSilence(DEFAULT_SKIP_SILENCE);
                    updateCallback();
                })
                .setPositiveButton(R.string.ok, (dialogInterface, i) -> updateCallback());

        return dialogBuilder.create();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Control Views
    //////////////////////////////////////////////////////////////////////////*/

    private void initUI() {
        // Tempo
        setText(binding.tempoMinimumText, PlayerHelper::formatSpeed, MIN_PLAYBACK_VALUE);
        setText(binding.tempoMaximumText, PlayerHelper::formatSpeed, MAX_PLAYBACK_VALUE);

        binding.tempoSeekbar.setMax(QUADRATIC_STRATEGY.progressOf(MAX_PLAYBACK_VALUE));
        setAndUpdateTempo(tempo);
        binding.tempoSeekbar.setOnSeekBarChangeListener(
                getTempoOrPitchSeekbarChangeListener(
                        QUADRATIC_STRATEGY,
                        this::onTempoSliderUpdated));

        registerOnStepClickListener(
                binding.tempoStepDown,
                () -> tempo,
                -1,
                this::onTempoSliderUpdated);
        registerOnStepClickListener(
                binding.tempoStepUp,
                () -> tempo,
                1,
                this::onTempoSliderUpdated);

        // Pitch - Percent
        setText(binding.pitchPercentMinimumText, PlayerHelper::formatPitch, MIN_PLAYBACK_VALUE);
        setText(binding.pitchPercentMaximumText, PlayerHelper::formatPitch, MAX_PLAYBACK_VALUE);

        binding.pitchPercentSeekbar.setMax(QUADRATIC_STRATEGY.progressOf(MAX_PLAYBACK_VALUE));
        setAndUpdatePitch(pitchPercent);
        binding.pitchPercentSeekbar.setOnSeekBarChangeListener(
                getTempoOrPitchSeekbarChangeListener(
                        QUADRATIC_STRATEGY,
                        this::onPitchPercentSliderUpdated));

        registerOnStepClickListener(
                binding.pitchPercentStepDown,
                () -> pitchPercent,
                -1,
                this::onPitchPercentSliderUpdated);
        registerOnStepClickListener(
                binding.pitchPercentStepUp,
                () -> pitchPercent,
                1,
                this::onPitchPercentSliderUpdated);

        // Pitch - Semitone
        binding.pitchSemitoneSeekbar.setOnSeekBarChangeListener(
                getTempoOrPitchSeekbarChangeListener(
                        SEMITONE_STRATEGY,
                        this::onPitchPercentSliderUpdated));

        registerOnSemitoneStepClickListener(
                binding.pitchSemitoneStepDown,
                -1,
                this::onPitchPercentSliderUpdated);
        registerOnSemitoneStepClickListener(
                binding.pitchSemitoneStepUp,
                1,
                this::onPitchPercentSliderUpdated);

        // Steps
        setupStepTextView(binding.stepSizeOnePercent, STEP_1_PERCENT_VALUE);
        setupStepTextView(binding.stepSizeFivePercent, STEP_5_PERCENT_VALUE);
        setupStepTextView(binding.stepSizeTenPercent, STEP_10_PERCENT_VALUE);
        setupStepTextView(binding.stepSizeTwentyFivePercent, STEP_25_PERCENT_VALUE);
        setupStepTextView(binding.stepSizeOneHundredPercent, STEP_100_PERCENT_VALUE);

        setAndUpdateStepSize(stepSize);

        // Bottom controls
        bindCheckboxWithBoolPref(
                binding.unhookCheckbox,
                R.string.playback_unhook_key,
                true,
                isChecked -> {
                    if (!isChecked) {
                        // when unchecked, slide back to the minimum of current tempo or pitch
                        setSliders(Math.min(pitchPercent, tempo));
                        updateCallback();
                    }
                });

        setAndUpdateSkipSilence(skipSilence);
        binding.skipSilenceCheckbox.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            skipSilence = isChecked;
            updateCallback();
        });

        bindCheckboxWithBoolPref(
                binding.adjustBySemitonesCheckbox,
                R.string.playback_adjust_by_semitones_key,
                false,
                this::showPitchSemitonesOrPercent
        );
    }

    private TextView setText(
            final TextView textView,
            final DoubleFunction<String> formatter,
            final double value
    ) {
        Objects.requireNonNull(textView).setText(formatter.apply(value));
        return textView;
    }

    private void registerOnStepClickListener(
            final TextView stepTextView,
            final DoubleSupplier currentValueSupplier,
            final double direction, // -1 for step down, +1 for step up
            final DoubleConsumer newValueConsumer
    ) {
        stepTextView.setOnClickListener(view -> {
            newValueConsumer.accept(
                    currentValueSupplier.getAsDouble() + 1 * stepSize * direction);
            updateCallback();
        });
    }

    private void registerOnSemitoneStepClickListener(
            final TextView stepTextView,
            final int direction, // -1 for step down, +1 for step up
            final DoubleConsumer newValueConsumer
    ) {
        stepTextView.setOnClickListener(view -> {
            newValueConsumer.accept(PlayerSemitoneHelper.semitonesToPercent(
                    PlayerSemitoneHelper.percentToSemitones(this.pitchPercent) + direction));
            updateCallback();
        });
    }

    private void setupStepTextView(
            final TextView textView,
            final double stepSizeValue
    ) {
        setText(textView, PlaybackParameterDialog::getPercentString, stepSizeValue)
                .setOnClickListener(view -> setAndUpdateStepSize(stepSizeValue));
    }

    private void setAndUpdateStepSize(final double newStepSize) {
        this.stepSize = newStepSize;

        binding.tempoStepUp.setText(getStepUpPercentString(newStepSize));
        binding.tempoStepDown.setText(getStepDownPercentString(newStepSize));

        binding.pitchPercentStepUp.setText(getStepUpPercentString(newStepSize));
        binding.pitchPercentStepDown.setText(getStepDownPercentString(newStepSize));
    }

    private void setAndUpdateSkipSilence(final boolean newSkipSilence) {
        this.skipSilence = newSkipSilence;
        binding.skipSilenceCheckbox.setChecked(newSkipSilence);
    }

    private void bindCheckboxWithBoolPref(
            @NonNull final CheckBox checkBox,
            @StringRes final int resId,
            final boolean defaultValue,
            @Nullable final Consumer<Boolean> onInitialValueOrValueChange
    ) {
        final boolean prefValue = PreferenceManager
                .getDefaultSharedPreferences(requireContext())
                .getBoolean(getString(resId), defaultValue);

        checkBox.setChecked(prefValue);

        if (onInitialValueOrValueChange != null) {
            onInitialValueOrValueChange.accept(prefValue);
        }

        checkBox.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            // save whether pitch and tempo are unhooked or not
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit()
                    .putBoolean(getString(resId), isChecked)
                    .apply();

            if (onInitialValueOrValueChange != null) {
                onInitialValueOrValueChange.accept(isChecked);
            }
        });
    }

    private void showPitchSemitonesOrPercent(final boolean semitones) {
        binding.pitchPercentControl.setVisibility(semitones ? View.GONE : View.VISIBLE);
        binding.pitchSemitoneControl.setVisibility(semitones ? View.VISIBLE : View.GONE);

        if (semitones) {
            // Recalculate pitch percent when changing to semitone
            // (as it could be an invalid semitone value)
            final double newPitchPercent = calcValidPitch(pitchPercent);

            // If the values differ set the new pitch
            if (this.pitchPercent != newPitchPercent) {
                if (DEBUG) {
                    Log.d(TAG, "Bringing pitchPercent to correct corresponding semitone: "
                            + "currentPitchPercent = " + pitchPercent + ", "
                            + "newPitchPercent = " + newPitchPercent
                    );
                }
                this.onPitchPercentSliderUpdated(newPitchPercent);
                updateCallback();
            }
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Sliders
    //////////////////////////////////////////////////////////////////////////*/

    private SeekBar.OnSeekBarChangeListener getTempoOrPitchSeekbarChangeListener(
            final SliderStrategy sliderStrategy,
            final DoubleConsumer newValueConsumer
    ) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(final SeekBar seekBar, final int progress,
                                          final boolean fromUser) {
                if (fromUser) { // ensure that the user triggered the change
                    newValueConsumer.accept(sliderStrategy.valueOf(progress));
                    updateCallback();
                }
            }

            @Override
            public void onStartTrackingTouch(final SeekBar seekBar) {
                // Do nothing
            }

            @Override
            public void onStopTrackingTouch(final SeekBar seekBar) {
                // Do nothing
            }
        };
    }

    private void onTempoSliderUpdated(final double newTempo) {
        if (!binding.unhookCheckbox.isChecked()) {
            setSliders(newTempo);
        } else {
            setAndUpdateTempo(newTempo);
        }
    }

    private void onPitchPercentSliderUpdated(final double newPitch) {
        if (!binding.unhookCheckbox.isChecked()) {
            setSliders(newPitch);
        } else {
            setAndUpdatePitch(newPitch);
        }
    }

    private void setSliders(final double newValue) {
        setAndUpdateTempo(newValue);
        setAndUpdatePitch(newValue);
    }

    private void setAndUpdateTempo(final double newTempo) {
        this.tempo = calcValidTempo(newTempo);

        binding.tempoSeekbar.setProgress(QUADRATIC_STRATEGY.progressOf(tempo));
        setText(binding.tempoCurrentText, PlayerHelper::formatSpeed, tempo);
    }

    private void setAndUpdatePitch(final double newPitch) {
        this.pitchPercent = calcValidPitch(newPitch);

        binding.pitchPercentSeekbar.setProgress(QUADRATIC_STRATEGY.progressOf(pitchPercent));
        binding.pitchSemitoneSeekbar.setProgress(SEMITONE_STRATEGY.progressOf(pitchPercent));
        setText(binding.pitchPercentCurrentText,
                PlayerHelper::formatPitch,
                pitchPercent);
        setText(binding.pitchSemitoneCurrentText,
                PlayerSemitoneHelper::formatPitchSemitones,
                pitchPercent);
    }

    private double calcValidTempo(final double newTempo) {
        return Math.max(MIN_PLAYBACK_VALUE, Math.min(MAX_PLAYBACK_VALUE, newTempo));
    }

    private double calcValidPitch(final double newPitch) {
        final double calcPitch =
                Math.max(MIN_PLAYBACK_VALUE, Math.min(MAX_PLAYBACK_VALUE, newPitch));

        if (!binding.adjustBySemitonesCheckbox.isChecked()) {
            return calcPitch;
        }

        return PlayerSemitoneHelper.semitonesToPercent(
                PlayerSemitoneHelper.percentToSemitones(calcPitch));
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Helper
    //////////////////////////////////////////////////////////////////////////*/

    private void updateCallback() {
        if (callback == null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "Updating callback: "
                    + "tempo = " + tempo + ", "
                    + "pitchPercent = " + pitchPercent + ", "
                    + "skipSilence = " + skipSilence
            );
        }
        callback.onPlaybackParameterChanged((float) tempo, (float) pitchPercent, skipSilence);
    }

    @NonNull
    private static String getStepUpPercentString(final double percent) {
        return '+' + getPercentString(percent);
    }

    @NonNull
    private static String getStepDownPercentString(final double percent) {
        return '-' + getPercentString(percent);
    }

    @NonNull
    private static String getPercentString(final double percent) {
        return PlayerHelper.formatPitch(percent);
    }

    public interface Callback {
        void onPlaybackParameterChanged(float playbackTempo, float playbackPitch,
                                        boolean playbackSkipSilence);
    }
}
