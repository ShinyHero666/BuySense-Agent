import { init, use } from "echarts/core";
import { BarChart, FunnelChart, GraphChart } from "echarts/charts";
import { GridComponent, TooltipComponent } from "echarts/components";
import { SVGRenderer } from "echarts/renderers";
import type { EChartsOption } from "echarts";
import { useEffect, useRef } from "react";

use([BarChart, FunnelChart, GraphChart, GridComponent, TooltipComponent, SVGRenderer]);

export function Chart({ option, className = "chart" }: { option: EChartsOption; className?: string }) {
  const element = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!element.current) return;
    const instance = init(element.current, undefined, { renderer: "svg" });
    instance.setOption(option);
    const resize = () => instance.resize();
    window.addEventListener("resize", resize);
    return () => {
      window.removeEventListener("resize", resize);
      instance.dispose();
    };
  }, [option]);
  return <div ref={element} className={className} />;
}
