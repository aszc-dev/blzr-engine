module.exports = function (config) {
  config.set({
    frameworks: ["cljs-test"],
    files: [
      "target/test.js",
      {
        pattern: "resources/blzr/engine/worklets/*.js",
        included: false,
        served: true,
        watched: false,
      },
    ],
    proxies: {
      "/worklets/": "/base/resources/blzr/engine/worklets/",
    },
    plugins: ["karma-cljs-test", "karma-chrome-launcher"],
    browsers: ["ChromeHeadlessMuted"],
    customLaunchers: {
      ChromeHeadlessMuted: {
        base: "ChromeHeadless",
        // Headless Chrome plays AudioContext output through the speakers.
        // --no-sandbox lets Chrome start as root inside a container.
        flags: ["--mute-audio", "--no-sandbox"],
      },
    },
    singleRun: true,
    processKillTimeout: 4000,
    // Offline renders and worklet fetches send no reporter traffic for long
    // stretches, which the 30 s default reads as a dead browser.
    browserNoActivityTimeout: 120000,
    client: {
      args: ["shadow.test.karma.init"],
    },
  });
};
