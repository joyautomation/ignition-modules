const path = require("path");

// A UMD bundle, because the gateway loads it through SystemJS with the AMD extra. Everything in `externals`
// is supplied by the gateway's own web app at runtime: bundling a second copy of React breaks the page.
module.exports = {
  entry: { mantleStatus: path.join(__dirname, "src/index.tsx") },
  output: {
    path: path.resolve(__dirname, "build/generated-resources/mounted/"),
    filename: "[name].js",
    library: "[name]",
    libraryTarget: "umd",
    umdNamedDefine: true,
    publicPath: "",
  },
  resolve: { extensions: [".ts", ".tsx", ".js"] },
  // React is the gateway's, not ours. The classic JSX runtime keeps this list to one entry: nothing else in
  // the page comes from a package, so there is no dependency on Inductive's private npm registry.
  externals: { react: "react", "react-dom": "react-dom" },
  module: {
    rules: [{ test: /\.[tj]sx?$/, exclude: /node_modules/, use: "babel-loader" }],
  },
  devtool: "source-map",
};
