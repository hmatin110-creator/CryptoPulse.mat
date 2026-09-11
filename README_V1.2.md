# CryptoPulse Android v1.2

## Auto-Learning
- Weekly WorkManager job.
- Reads stored weights from SharedPreferences.
- Runs walk-forward optimization on BTC daily history.
- Evaluates optimized weights against the currently stored weights on a separate holdout slice.
- Accepts new weights only when:
  - holdout has at least 30 evaluated signals;
  - objective improves by at least 0.01;
  - Brier score does not materially worsen;
  - max drawdown does not materially worsen.
- Otherwise previous weights are retained.

## Data note
Binance Futures exposes historical funding, open-interest statistics, long/short ratios and taker buy/sell volume endpoints, with documented recent-history windows for several of these endpoints. The app continues to treat these as supplementary features rather than proof of capital inflow/outflow.

## Build from a phone
The repository now includes `.github/workflows/build-apk.yml`. GitHub Actions builds the debug APK on a cloud runner, so Android Studio is not required on the phone. See `PHONE_BUILD.md` for the exact steps.
