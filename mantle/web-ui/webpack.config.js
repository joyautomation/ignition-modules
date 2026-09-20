const path = require("path");

// A UMD bundle, because the gateway loads it through SystemJS with the AMD extra. Everything in `externals`
// is supplied by the gateway's own web app at runtime: bundling a second copy of React breaks the page.
module.exports = {
  entry: { mantle: path.join(__dirname, "src/index.tsx") },
  output: {
    path: path.resolve(__dirname, "build/generated-resources/mounted/"),
    filename: "[name].js",
    library: "[name]",
    libraryTarget: "umd",
    umdNamedDefine: true,
    publicPath: "",
    // Everything in here is copied into the .modl as-is, so a bundle left behind by an earlier name would
    // ship — and be served — forever.
    clean: true,
  },
  resolve: { extensions: [".ts", ".tsx", ".js"] },
  // All of these are the gateway's, not ours, and are resolved at runtime through its SystemJS import map.
  // Bundling a second copy of React breaks the page; the two @inductiveautomation packages *cannot* be
  // bundled at all, because they are published only to Inductive's private npm registry. Listing them here
  // is how a module uses them without ever installing them — see src/ia-gateway-lib.d.ts.
  externals: {
    react: "react",
    "react-dom": "react-dom",
    "@inductiveautomation/ignition-gateway-lib": "@inductiveautomation/ignition-gateway-lib",
    "@inductiveautomation/ignition-web-ui": "@inductiveautomation/ignition-web-ui",
  },
  module: {
    rules: [{ test: /\.[tj]sx?$/, exclude: /node_modules/, use: "babel-loader" }],
  },
  devtool: "source-map",
};
