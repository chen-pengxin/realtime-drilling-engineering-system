<template><TrajectoryPlot :trajectories="curves" view="3d" :nearest-pair="nearestPair" :risk-segments="riskSegments"/></template>
<script setup>
import {computed} from 'vue'
import TrajectoryPlot from '@/components/TrajectoryPlot'
import {trajectoryColors} from '@/utils/trajectoryColors'
const props=defineProps({points:{type:Array,default:()=>[]},trajectories:{type:Array,default:()=>[]},nearestPair:{type:Object,default:null},riskSegments:{type:Array,default:()=>[]},riskLevel:String})
const curves=computed(()=>props.trajectories.length?props.trajectories:props.points.length?[{id:'current',kind:'ACTUAL',label:'当前实钻轨迹',color:trajectoryColors.ACTUAL,points:props.points}]:[])
</script>
