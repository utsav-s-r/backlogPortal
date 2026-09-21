// Fixture standing in for a Vite route chunk. ./mvnw test bundles no frontend, so without a file
// under classpath:/static/assets/ there is nothing for StaticAssetCachingTest to probe and its
// cache-header assertions would pass on a 404. The name mimics Vite's <name>-<hash>.js output.
export const chunk = "test";
