import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  build: {
    // The ECharts core is intentionally shared by the quality and decision
    // views; 650 KiB keeps the warning threshold aligned with that split.
    chunkSizeWarningLimit: 650
  },
  server: {
    proxy: {
      "/api": "http://127.0.0.1:19090",
      "/health": "http://127.0.0.1:19090",
      "/metrics": "http://127.0.0.1:19090"
    }
  }
});
