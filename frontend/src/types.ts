export type Machine={id:string;name:string;synthetic:boolean;targetDate:string;scope:Record<string,boolean>;scopeReviewer:string};
export type Fact={id:string;machineId:string;evidenceId:string;subject:string;key:string;value:unknown;quote:string;locator:string;nature:string;reviewer:string|null;reviewNote:string|null};
export type Evidence={id:string;name:string;text:string;sha256:string;processingStatus:string;error:string|null};
export type Rule={id:string;version:string;law:string;reference:string;sourceUrl:string;sourceHash:string;originalText:string;interpretation:string;effectiveFrom:string;status:string;reviewer:string;controls:{id:string;factKey:string;question:string;evidenceType:string}[]};
export type ControlResult={requirementId:string;version:string;controlId:string;status:string;reason:string;task:{evidence:Fact[];applicability:string};decision:{status:string;provider:string;model:string;abstain:boolean;calibrated:boolean;answers:unknown;error:string|null}};
export type Assessment={id:string;createdAt:string;status:string;controls:ControlResult[];rules:Rule[]};
export type Request={id:string;assessmentId:string;key:string;question:string;controlIds:string[];status:string};
export type Knowledge={machine:Machine;facts:Fact[];evidence:Evidence[];entities:{id:string;type:string;name:string}[];relations:unknown[]};
